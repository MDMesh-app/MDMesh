package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.AgentCommandDAO;
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
 * still targets when its last one expired (the result was lost: the agent died mid-install, say) or none was ever
 * queued. Without this, such a device shows "queued" forever, since the rollout only enqueues on create and promote.
 */
@Singleton
public class RolloutRequeuer {
    private static final Logger logger = LoggerFactory.getLogger(RolloutRequeuer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RolloutDAO rolloutDAO;
    private final AgentCommandDAO commandDAO;

    @Inject
    public RolloutRequeuer(RolloutDAO rolloutDAO, AgentCommandDAO commandDAO) {
        this.rolloutDAO = rolloutDAO;
        this.commandDAO = commandDAO;
    }

    /** Insert the rollout's app.install for one device (the caller wakes it when it is not checking in right now). */
    public void enqueue(AgentRollout r, String deviceNumber, long now) {
        AgentCommand cmd = new AgentCommand();
        cmd.setDeviceNumber(deviceNumber);
        cmd.setType("app.install");
        cmd.setPayload(installPayload(r));
        cmd.setRequiresCapability(RolloutProgress.INSTALL_CAPABILITY);
        cmd.setStatus("pending");
        cmd.setCreatedAt(now);
        commandDAO.insert(cmd);
    }

    /** Called from check-in, after stale commands were expired. @return true when the update was queued. Never throws. */
    public boolean requeue(Device device, long now) {
        try {
            if (device == null) return false;
            AgentRollout r = rolloutDAO.findActiveByCustomer(device.getCustomerId());
            if (r == null) return false;
            String number = device.getNumber();
            if (!"fleet".equals(r.getStage()) && !rolloutDAO.isCanary(r.getId(), number)) return false;
            // The newest command for this rollout decides; any in-flight install of the package (even one queued
            // before the rollout) means the device is already installing it.
            long since = r.getCreatedAt() == null ? 0L : r.getCreatedAt();
            String latest = null;
            boolean inFlight = false;
            for (AgentCommand c : rolloutDAO.listInstalls(number)) {   // newest first
                if (!RolloutProgress.isFor(r.getPackageName(), c)) continue;
                inFlight |= "pending".equals(c.getStatus()) || "delivered".equals(c.getStatus());
                if (latest == null && c.getCreatedAt() != null && c.getCreatedAt() >= since) latest = c.getStatus();
            }
            if (RolloutProgress.classify(r.getTargetVersion(), rolloutDAO.findDeviceRow(number), inFlight)
                    != RolloutProgress.Status.OUTSTANDING || !RolloutProgress.shouldRequeue(latest)) {
                return false;
            }
            enqueue(r, number, now);
            logger.info("Rollout {}: app.install queued again for {} (last: {})", r.getId(), number, latest == null ? "none" : latest);
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
