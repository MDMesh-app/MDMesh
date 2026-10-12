/*
 * MDMesh agent-v1: queues a device's configuration apps for installation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.rest.json.InstallIntegrity;
import com.hmdm.rest.json.InstallPayloadBuilder;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationVersion;
import com.hmdm.persistence.domain.Device;
import com.hmdm.util.RolloutProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * Turns a device's configuration app list into queued {@code app.install} commands — the piece
 * that makes a configuration a "golden image" for the command-driven agent (which never reads the
 * configuration itself). Used at enrollment and by the admin "sync apps" action.
 */
@Singleton
public class ConfigAppInstaller {

    private static final Logger logger = LoggerFactory.getLogger(ConfigAppInstaller.class);

    /** Action value in configurationApplications meaning "install this app". */
    private static final int ACTION_INSTALL = 1;

    private final UnsecureDAO unsecureDAO;
    private final AgentCommandDAO commandDAO;
    private final AgentWakeHub wakeHub;
    private final ApkHashResolver hashResolver;

    @Inject
    public ConfigAppInstaller(UnsecureDAO unsecureDAO, AgentCommandDAO commandDAO, AgentWakeHub wakeHub,
                              ApkHashResolver hashResolver) {
        this.unsecureDAO = unsecureDAO;
        this.commandDAO = commandDAO;
        this.wakeHub = wakeHub;
        this.hashResolver = hashResolver;
    }

    /**
     * Queue an {@code app.install} for every action=install app of the device's configuration
     * that has a real hosted APK URL and a known SHA-256 (an app whose hash can't be had is skipped and logged:
     * the agent refuses an unhashed install). Returns the number queued. Never throws — callers treat
     * this as best-effort (enrollment must not fail because an app list is dirty).
     */
    public int enqueueConfigApps(Device device) {
        if (device == null || device.getConfigurationId() == null) {
            return 0;
        }
        int queued = 0;
        try {
            List<Application> apps = unsecureDAO.getPlainConfigurationApplications(
                    device.getCustomerId(), device.getConfigurationId());
            long now = System.currentTimeMillis();
            for (Application app : apps) {
                if (app == null || app.getAction() != ACTION_INSTALL) {
                    continue;
                }
                String url = firstUsableUrl(app);
                boolean hasParts = app.getParts() != null && !app.getParts().trim().isEmpty();
                if ((url == null && !hasParts) || app.getPkg() == null || app.getPkg().trim().isEmpty()) {
                    // Catalog placeholder / web app / seed leftover — nothing downloadable.
                    continue;
                }
                String payload;
                try {
                    payload = installPayload(app, url);
                } catch (IllegalArgumentException e) {
                    logger.warn("Not queuing {} for device {}: {}", app.getPkg(), device.getNumber(), e.getMessage());
                    continue;
                }
                AgentCommand cmd = new AgentCommand();
                cmd.setDeviceNumber(device.getNumber());
                cmd.setType("app.install");
                cmd.setPayload(payload);
                cmd.setRequiresCapability(RolloutProgress.INSTALL_CAPABILITY);
                cmd.setStatus("pending");
                cmd.setCreatedAt(now);
                commandDAO.insert(cmd);
                queued++;
            }
            if (queued > 0) {
                wakeHub.wake(device.getNumber(), "commands");
            }
        } catch (Exception e) {
            logger.warn("Failed to queue configuration apps for device {}", device.getNumber(), e);
        }
        return queued;
    }

    /**
     * The app's install payload with a SHA-256 on the APK (or every bundle part). The version's stored hash is
     * reused; a version without one (a row from before hashes were required, or an external URL) is hashed once —
     * from the files directory for an APK uploaded here, else by download — and the result saved on the version.
     */
    private String installPayload(Application app, String url) {
        ApplicationVersion version = app.getUsedVersionId() == null ? null
                : unsecureDAO.findApplicationVersionById(app.getUsedVersionId());
        String versionUrl = version == null || version.getUrl() == null ? null : version.getUrl().trim();
        String stored = version == null ? null : InstallIntegrity.normalizeHex(version.getApkHash());
        String raw = InstallPayloadBuilder.build(app.getPkg().trim(), app.getVersionCode(), url, app.getParts());
        return InstallIntegrity.ensureSha256(raw, u -> {
            boolean versionApk = u.equals(versionUrl);
            if (versionApk && stored != null) {
                return stored;
            }
            String hex = hashResolver.sha256Hex(u);
            if (versionApk) {
                unsecureDAO.saveApkFileHash(version.getId(), InstallIntegrity.toApkHash(hex));
            }
            return hex;
        });
    }

    /**
     * Only http(s) URLs are installable by the agent, and the upstream seed ships literal
     * placeholder URLs (e.g. {@code .../_HMDM_APK_}) that must never reach a device. Neither may the seed's other
     * h-mdm.com apps: a deployment updated from the console never ran post_seed.sql, which removes them.
     */
    static String firstUsableUrl(Application app) {
        for (String candidate : new String[]{app.getUrl(), app.getUrlArm64(), app.getUrlArmeabi()}) {
            if (candidate == null) {
                continue;
            }
            String u = candidate.trim();
            if ((u.startsWith("https://") || u.startsWith("http://")) && !u.contains("_HMDM_") && !isHeadwindHost(u)) {
                return u;
            }
        }
        return null;
    }

    private static boolean isHeadwindHost(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase("h-mdm.com") || host.toLowerCase().endsWith(".h-mdm.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
