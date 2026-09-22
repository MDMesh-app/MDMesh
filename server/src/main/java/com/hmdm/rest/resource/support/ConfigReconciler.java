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
import java.util.concurrent.ConcurrentHashMap;

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
            // Steady state (device already applied this revision) must cost only the config + apps
            // selects: skip the command-queue lookups entirely. decide() would return NOOP anyway.
            if (doc.getRevision().equals(appliedRevision)) return false;
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
            // A broken configuration fails on EVERY check-in of every device on it: WARN at most once
            // per minute per configuration id, DEBUG otherwise.
            Integer cfgId = device == null ? null : device.getConfigurationId();
            String number = device == null ? "?" : device.getNumber();
            if (shouldWarn(cfgId == null ? Integer.valueOf(-1) : cfgId, System.currentTimeMillis())) {
                logger.warn("config reconcile skipped for {} (configuration {})", number, cfgId, e);
            } else {
                logger.debug("config reconcile skipped for {} (configuration {})", number, cfgId, e);
            }
            return false;
        }
    }

    static final long WARN_INTERVAL_MS = 60_000L;
    private final ConcurrentHashMap<Integer, Long> lastWarnAt = new ConcurrentHashMap<Integer, Long>();

    /** True when no WARN was logged for this configuration in the last {@link #WARN_INTERVAL_MS}. */
    boolean shouldWarn(Integer configurationId, long now) {
        Long prev = lastWarnAt.get(configurationId);
        if (prev != null && now - prev < WARN_INTERVAL_MS) return false;
        if (prev == null) return lastWarnAt.putIfAbsent(configurationId, now) == null;
        return lastWarnAt.replace(configurationId, prev, now);
    }
}
