package com.hmdm.util;

import com.hmdm.persistence.domain.Application;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Calculates the installable application versions newly requested by a configuration edit.
 *
 * <p>A configuration save replaces its application links wholesale. Comparing selected version
 * identifiers before and after the replacement lets the agent command path install newly added
 * apps (and deliberate version changes) without re-installing every application when an unrelated
 * configuration setting changes.</p>
 */
public final class ConfigurationAppDelta {
    /** configurationApplications.action value meaning install. */
    public static final int ACTION_INSTALL = 1;

    private ConfigurationAppDelta() {}

    /**
     * Returns version IDs that are install-marked after an edit but were not install-marked before
     * it. Null/malformed entries are ignored: they cannot identify a concrete APK version.
     */
    public static Set<Integer> addedInstallVersionIds(List<Application> before, List<Application> after) {
        Set<Integer> prior = installVersionIds(before);
        Set<Integer> added = installVersionIds(after);
        added.removeAll(prior);
        return added.isEmpty() ? Collections.<Integer>emptySet() : Collections.unmodifiableSet(added);
    }

    private static Set<Integer> installVersionIds(List<Application> apps) {
        Set<Integer> result = new HashSet<>();
        if (apps == null) return result;
        for (Application app : apps) {
            if (app != null && app.getAction() == ACTION_INSTALL && app.getUsedVersionId() != null) {
                result.add(app.getUsedVersionId());
            }
        }
        return result;
    }
}
