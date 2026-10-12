package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.RolloutDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.Device;
import com.hmdm.util.RolloutProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Queues a rollout's {@code app.install}, and from every check-in queues it again for a device the active rollout
 * still targets when none was queued, the last one expired before reaching it, or the last one failed or expired
 * after reaching it (the result was lost: the agent died mid-install, say) within {@link RolloutProgress#MAX_ATTEMPTS}. Without this, such a device shows "queued" forever,
 * since the rollout only enqueues on create and promote, and v0.3.2-and-older agents can lose a result.
 */
@Singleton
public class RolloutRequeuer {
    private static final Logger logger = LoggerFactory.getLogger(RolloutRequeuer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RolloutDAO rolloutDAO;

    @Inject
    public RolloutRequeuer(RolloutDAO rolloutDAO) {
        this.rolloutDAO = rolloutDAO;
    }

    /** Insert the rollout's app.install for one device unless it already has it in flight (the caller wakes it when
     *  it is not checking in right now). @return true when queued. */
    public boolean enqueue(AgentRollout r, String deviceNumber, long now) {
        AgentCommand cmd = new AgentCommand();
        cmd.setDeviceNumber(deviceNumber);
        cmd.setType("app.install");
        cmd.setPayload(installPayload(r));
        cmd.setRequiresCapability(RolloutProgress.INSTALL_CAPABILITY);
        cmd.setStatus("pending");
        cmd.setCreatedAt(now);
        return rolloutDAO.insertIfNotInFlight(cmd);
    }

    /** Called from check-in, after stale commands were expired. @return true when the update was queued. Never throws. */
    public boolean requeue(Device device, long now) {
        try {
            if (device == null) return false;
            AgentRollout r = rolloutDAO.findActiveByCustomer(device.getCustomerId());
            if (r == null) return false;
            String number = device.getNumber();
            if (!"fleet".equals(r.getStage()) && !rolloutDAO.isCanary(r.getId(), number)) return false;
            // Steady state stays cheap: an updated or ineligible device stops here, before its commands are read.
            if (RolloutProgress.classify(r.getTargetVersion(), rolloutDAO.findDeviceRow(number), false)
                    != RolloutProgress.Status.OUTSTANDING) return false;
            // This rollout's commands for the device (queued since it was created); the newest one decides.
            AgentCommand latest = null;
            int attempts = 0;   // commands that reached the device
            for (AgentCommand c : rolloutDAO.listInstallsSince(number, r.getCreatedAt() == null ? 0L : r.getCreatedAt())) {
                if (!RolloutProgress.isFor(r.getPackageName(), c)) continue;
                if (latest == null) latest = c;   // newest first
                if (c.getDeliveredAt() != null) attempts++;
            }
            if (!RolloutProgress.shouldRequeue(latest == null ? null : latest.getStatus(),
                    latest != null && latest.getDeliveredAt() != null, attempts)) return false;
            if (!enqueue(r, number, now)) return false;
            logger.info("Rollout {}: app.install queued again for {} (last: {}, attempts: {})", r.getId(), number,
                    latest == null ? "none" : latest.getStatus(), attempts);
            return true;
        } catch (Exception e) {
            logger.warn("rollout requeue skipped for {}", device == null ? "?" : device.getNumber(), e);
            return false;
        }
    }

    private static String installPayload(AgentRollout r) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("url", r.getApkUrl());
        p.put("packageName", r.getPackageName());
        if (r.getApkVersionCode() != null) p.put("versionCode", r.getApkVersionCode());
        if (r.getApkSha256() != null) p.put("sha256", r.getApkSha256());
        p.put("runAfterInstall", false);
        try {
            return MAPPER.writeValueAsString(p);
        } catch (Exception e) {
            return "{}"; // unreachable for a plain ObjectNode
        }
    }
}
