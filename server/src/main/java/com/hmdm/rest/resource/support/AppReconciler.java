/*
 * MDMesh agent-v1: installs a configuration's apps on its devices at check-in.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.RolloutDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Device;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.AppReconcileDecision;
import com.hmdm.util.RolloutProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a device's apps in line with its configuration's install set. Called from every check-in, next to
 * {@link ConfigReconciler}: when the set differs from the one stored for the device ({@code device_state.appsRevision}),
 * each version it added that the device still needs ({@link AppReconcileDecision}, from its install history) gets an
 * {@code app.install} that rides this response. One path for every way the set changes: a configuration saved
 * with a new app or version, Deploy's "Add to a configuration", the device moved to another configuration, and a
 * missed wake (the next regular check-in). Saves and moves still wake the devices, so it happens in seconds.
 *
 * <p>The steady state is one plain select (the set's version ids): the new set is stored once every app it added is
 * installed or given up on, and until then each check-in looks again, so a failed install is retried with backoff. A
 * device seen without a stored set (its first check-in after the update that added the column, or after enrollment,
 * which queues the whole set itself) records the current one and queues nothing, so the update sends no installs to
 * the fleet. Apps already in that set are left as they are; Install config apps re-sends them.</p>
 */
@Singleton
public class AppReconciler {
    private static final Logger logger = LoggerFactory.getLogger(AppReconciler.class);

    /** Install history read per reconcile: an install older than this is queued again, and the agent skips it. */
    static final int HISTORY_LIMIT = 500;

    private final UnsecureDAO unsecureDAO;
    private final AgentCommandDAO commandDAO;
    private final RolloutDAO rolloutDAO;
    private final ConfigAppInstaller installer;

    @Inject
    public AppReconciler(UnsecureDAO unsecureDAO, AgentCommandDAO commandDAO, RolloutDAO rolloutDAO,
                         ConfigAppInstaller installer) {
        this.unsecureDAO = unsecureDAO;
        this.commandDAO = commandDAO;
        this.rolloutDAO = rolloutDAO;
        this.installer = installer;
    }

    /**
     * @param appsRevision the revision stored for the device (null = none yet: record a baseline)
     * @return how many app.install were queued. Never throws.
     */
    public int reconcile(Device device, Set<String> deviceTokens, String appsRevision, long now) {
        try {
            // An agent that can't install silently pays nothing, and is never sent an install.
            if (!AgentCapabilityTokens.isAllowed(RolloutProgress.INSTALL_CAPABILITY, deviceTokens)) return 0;
            if (device == null || device.getConfigurationId() == null) return 0;
            String number = device.getNumber();
            String current = AppReconcileDecision.revision(
                    unsecureDAO.getConfigurationInstallVersionIds(device.getConfigurationId()));
            if (current.equals(appsRevision)) return 0;
            if (appsRevision == null) {
                // First sighting: record a baseline only, so a server update does not re-send to the whole fleet.
                commandDAO.updateAppsRevision(number, current);
                return 0;
            }
            // Only the versions this change added: the others were in the set the device was last reconciled to.
            Set<Integer> reconciled = AppReconcileDecision.versionIds(appsRevision);
            List<AgentCommand> history = commandDAO.listLatestAppCommands(number, HISTORY_LIMIT);
            boolean settled = true;
            int queued = 0;
            for (Application app : installer.installableApps(device)) {
                if (reconciled.contains(app.getUsedVersionId())) continue;
                AppReconcileDecision.State state = AppReconcileDecision.decide(app.getPkg().trim(),
                        app.getVersionCode(), history, now);
                if (state == AppReconcileDecision.State.QUEUE) {
                    AgentCommand cmd = installer.installCommand(device, app, now);
                    if (!"pending".equals(cmd.getStatus())) {
                        // No SHA-256: recorded failed, never sent, and not tried again (AppReconcileDecision).
                        commandDAO.insert(cmd);
                        continue;
                    }
                    if (rolloutDAO.insertIfNotInFlight(cmd)) queued++;
                    settled = false;
                } else if (!AppReconcileDecision.settled(state)) {
                    settled = false;
                }
            }
            if (settled) commandDAO.updateAppsRevision(number, current);
            if (queued > 0) {
                logger.info("Configuration apps for {}: {} app.install queued (configuration {})", number, queued,
                        device.getConfigurationId());
            }
            return queued;
        } catch (Exception e) {
            // A broken configuration fails on EVERY check-in of every device on it: WARN at most once
            // per minute per configuration id, DEBUG otherwise.
            Integer cfgId = device == null ? null : device.getConfigurationId();
            String number = device == null ? "?" : device.getNumber();
            if (shouldWarn(cfgId == null ? Integer.valueOf(-1) : cfgId, System.currentTimeMillis())) {
                logger.warn("app reconcile skipped for {} (configuration {})", number, cfgId, e);
            } else {
                logger.debug("app reconcile skipped for {} (configuration {})", number, cfgId, e);
            }
            return 0;
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
