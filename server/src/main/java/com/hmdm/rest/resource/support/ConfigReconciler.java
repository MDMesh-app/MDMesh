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
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Desired-state reconciliation for the command-driven agent. Called from every check-in: if the device's
 * reported applied revision differs from its configuration's current revision, queue ONE {@code config.apply}.
 * The document is rebuilt from the Configuration row each time (no stored revision to drift), using the
 * optimized app query (two plain selects per capable check-in — no temp table).
 *
 * <p>It is also re-sent when the device runs a different agent version than the one the last completed
 * config.apply was queued for (a newer agent may support policy keys the old one reported {@code unsupported}),
 * and keeps being re-sent until one queued for the new version comes back done (a failed or expired attempt
 * keeps the normal backoff).</p>
 *
 * <p>A config.apply handed out before a check-in that carries no result for it is ended as abandoned, so it never
 * holds back the configuration: the agent runs commands inside its check-in and reports them on the next one, so it
 * restarted mid-apply or never got the response. At most one config.apply is open per device (a partial unique
 * index); a check-in that loses the race to queue it just leaves it to the one that won.</p>
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

    /**
     * @param agentVersion       the version the device reports in this check-in (null when unknown)
     * @param configAgentVersion the version that last completed config.apply (null = no baseline yet)
     * @param receivedAt         when this check-in reached the server, before its results were recorded: a
     *                           config.apply delivered earlier and still open is not running on the device
     * @return true when a config.apply was enqueued. Never throws.
     */
    public boolean reconcile(Device device, Set<String> deviceTokens, String appliedRevision,
                             String agentVersion, String configAgentVersion, long now, long receivedAt) {
        try {
            // Cost short-circuit only: an old agent without the capability pays nothing (no config/app
            // query at all). ConfigReconcileDecision.decide still re-checks this same gate below.
            if (!AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, deviceTokens)) return false;
            DesiredConfig doc = currentDocument(device);
            if (doc == null) return false;
            String number = device.getNumber();
            if (configAgentVersion == null && agentVersion != null) {
                // First sighting: record a baseline only, so a server upgrade does not re-send to the whole fleet.
                commandDAO.updateConfigAgentVersion(number, agentVersion);
            }
            boolean agentChanged = ConfigReconcileDecision.agentChanged(agentVersion, configAgentVersion);
            // Steady state: skip the command-queue lookups (decide() would return NONE anyway).
            if (doc.getRevision().equals(appliedRevision) && !agentChanged) return false;
            // Checked only on drift (or an agent change): an in-sync device has nothing waiting on it.
            if (commandDAO.abandonDelivered(number, DesiredConfigBuilder.COMMAND_TYPE, receivedAt, now,
                    ConfigReconcileDecision.ABANDONED_DETAIL) > 0) {
                logger.info("config.apply for {} ended: the agent checked in again without its result", number);
            }
            boolean open = commandDAO.hasOpenOfType(number, DesiredConfigBuilder.COMMAND_TYPE);
            List<AgentCommand> recent = open ? Collections.<AgentCommand>emptyList()
                    : commandDAO.listLatestOfType(number, DesiredConfigBuilder.COMMAND_TYPE, 2);
            AgentCommand latest = recent.isEmpty() ? null : recent.get(0);
            AgentCommand previous = recent.size() < 2 ? null : recent.get(1);
            if (ConfigReconcileDecision.decide(deviceTokens, doc.getRevision(), appliedRevision, open, latest, previous,
                    now, agentChanged) != ConfigReconcileDecision.Action.ENQUEUE) {
                return false;
            }
            AgentCommand cmd = new AgentCommand();
            cmd.setDeviceNumber(number);
            cmd.setType(DesiredConfigBuilder.COMMAND_TYPE);
            cmd.setPayload(DesiredConfigBuilder.toPayloadJson(doc));
            cmd.setRequiresCapability(DesiredConfigBuilder.CAPABILITY);
            cmd.setAgentVersion(agentVersion);
            cmd.setStatus("pending");
            cmd.setCreatedAt(now);
            try {
                commandDAO.insert(cmd);
            } catch (Exception e) {
                if (!isUniqueViolation(e)) throw e;
                logger.debug("config.apply for {} already queued by a concurrent check-in", number);
                return false;
            }
            logger.info("config.apply queued for {} (revision {} -> {}, agent {})", number, appliedRevision,
                    doc.getRevision(), agentChanged ? configAgentVersion + " -> " + agentVersion : agentVersion);
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

    /** The insert hit the one-open-config.apply-per-device index (PostgreSQL unique_violation). */
    static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException && "23505".equals(((SQLException) t).getSQLState())) return true;
        }
        return false;
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
