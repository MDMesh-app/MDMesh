package com.hmdm.rest.resource.support;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.ConfigReconcileDecision;
import com.hmdm.util.DesiredConfigBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Set;

/**
 * Desired-state reconciliation for the command-driven agent. Called from every check-in: if the device's
 * reported applied revision differs from its configuration's current revision, queue ONE {@code config.apply}.
 * The document is rebuilt from the Configuration row each time (no stored revision to drift), using the
 * optimized app query (two plain selects per capable check-in — no temp table).
 */
@Singleton
public class ConfigReconciler {
    private static final Logger logger = LoggerFactory.getLogger(ConfigReconciler.class);

    private final UnsecureDAO unsecureDAO;
    private final AgentCommandDAO commandDAO;

    @Inject
    public ConfigReconciler(UnsecureDAO unsecureDAO, AgentCommandDAO commandDAO) {
        this.unsecureDAO = unsecureDAO;
        this.commandDAO = commandDAO;
    }

    /** The desired-state document for the device's configuration, or null when it has none. */
    public DesiredConfig currentDocument(Device device) {
        if (device == null || device.getConfigurationId() == null) return null;
        Configuration cfg = unsecureDAO.getConfigurationById(device.getConfigurationId());
        if (cfg == null) return null;
        List<Application> apps = unsecureDAO.getPlainConfigurationAppsOptimized(cfg.getId());
        return DesiredConfigBuilder.build(cfg, apps);
    }

    public String currentRevision(Device device) {
        DesiredConfig d = currentDocument(device);
        return d == null ? null : d.getRevision();
    }

    /** @return true when a config.apply was enqueued. Never throws. */
    public boolean reconcile(Device device, Set<String> deviceTokens, String appliedRevision, long now) {
        try {
            // Cost short-circuit only: an old agent without the capability pays nothing (no config/app
            // query at all). ConfigReconcileDecision.decide still re-checks this same gate below.
            if (!AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, deviceTokens)) return false;
            DesiredConfig doc = currentDocument(device);
            if (doc == null) return false;
            String number = device.getNumber();
            boolean open = commandDAO.hasOpenOfType(number, DesiredConfigBuilder.COMMAND_TYPE);
            AgentCommand latest = open ? null : commandDAO.findLatestOfType(number, DesiredConfigBuilder.COMMAND_TYPE);
            if (ConfigReconcileDecision.decide(deviceTokens, doc.getRevision(), appliedRevision, open, latest, now)
                    != ConfigReconcileDecision.Action.ENQUEUE) {
                return false;
            }
            AgentCommand cmd = new AgentCommand();
            cmd.setDeviceNumber(number);
            cmd.setType(DesiredConfigBuilder.COMMAND_TYPE);
            cmd.setPayload(DesiredConfigBuilder.toPayloadJson(doc));
            cmd.setRequiresCapability(DesiredConfigBuilder.CAPABILITY);
            cmd.setStatus("pending");
            cmd.setCreatedAt(now);
            commandDAO.insert(cmd);
            logger.info("config.apply queued for {} (revision {} -> {})", number, appliedRevision, doc.getRevision());
            return true;
        } catch (Exception e) {
            logger.warn("config reconcile skipped for {}", device == null ? "?" : device.getNumber(), e);
            return false;
        }
    }
}
