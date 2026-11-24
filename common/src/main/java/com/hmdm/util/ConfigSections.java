package com.hmdm.util;

import com.hmdm.rest.json.agent.DesiredAppPermissions;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredConnectivity;
import com.hmdm.rest.json.agent.DesiredKiosk;
import com.hmdm.rest.json.agent.DesiredLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sections of a {@code config.apply} document (its top-level keys besides {@code revision} and
 * {@code configurationId}), one row each, as {@code proto/config-sections.json} lists them, and the rules for which of
 * them a device is sent and which it applies.
 *
 * <p>An agent releases a section it is no longer sent (absent means "not managed"), so a section is left out of a
 * device's document only on what its agent advertises: a section with a capability ({@code appPermissions}) is sent
 * only to an agent that advertises the capability. Every other section is always sent: an agent ignores a section it
 * doesn't know, and nothing it reports, its version least of all, can make the server send less.</p>
 *
 * <p>What a device applies decides only whether it needs an agent update ({@link #notApplied}); never what it is sent,
 * nor whether it runs the current revision. An agent from 0.6.0 advertises {@code configSections}, flattened to
 * {@code config.<name>}, and applies exactly those. An older agent applies a section with a capability
 * when it advertises it, and any other section unless it reports a version below the section's {@code since}. Only a
 * version that reads as one, from 0.3.0 on, counts: a missing, unreadable or older one (a development build's) is taken
 * to apply them all. An empty {@code configSections} list flattens to no tokens, so it counts as no list.</p>
 */
public final class ConfigSections {

    /** The first release whose agent applies config.apply; every section's {@code since} is this or later. */
    static final String FIRST_CONFIG_APPLY = "0.3.0";

    /** Capability token prefix of the sections an agent from 0.6.0 applies ({@code capabilities.configSections}). */
    public static final String TOKEN_PREFIX = "config.";

    /** One section: its key in the document, the release whose agent first applies it, and how to read and clear it. */
    public static final class Section {
        private final String name;
        private final String since;
        private final String capability;
        private final Function<DesiredConfig, Object> get;
        private final BiConsumer<DesiredConfig, Object> set;

        private Section(String name, String since, String capability, Function<DesiredConfig, Object> get,
                        BiConsumer<DesiredConfig, Object> set) {
            this.name = name;
            this.since = since;
            this.capability = capability;
            this.get = get;
            this.set = set;
        }

        public String getName() { return name; }

        /** The release whose agent first applies this section. */
        public String getSince() { return since; }

        /**
         * The capability an agent advertises when it applies this section: the section is sent only to such an agent.
         * Null when none: the section is sent to every agent.
         */
        public String getCapability() { return capability; }

        /** The capability token an agent from 0.6.0 advertises when it applies this section ({@code config.<name>}). */
        public String getToken() { return TOKEN_PREFIX + name; }

        public Object valueIn(DesiredConfig doc) { return get.apply(doc); }

        void setIn(DesiredConfig doc, Object value) { set.accept(doc, value); }

        @Override
        public String toString() { return name; }
    }

    /** In the order agents apply them. A new section appends its row, here and in proto/config-sections.json. */
    @SuppressWarnings("unchecked")
    public static final List<Section> ALL = Collections.unmodifiableList(Arrays.asList(
            new Section("policies", "0.3.0", null,
                    DesiredConfig::getPolicies, (d, v) -> d.setPolicies((Map<String, Boolean>) v)),
            new Section(DesiredConfigBuilder.KEY_KIOSK, "0.3.0", null,
                    DesiredConfig::getKiosk, (d, v) -> d.setKiosk((DesiredKiosk) v)),
            new Section(DesiredConfigBuilder.KEY_LOCATION, "0.3.0", null,
                    DesiredConfig::getLocation, (d, v) -> d.setLocation((DesiredLocation) v)),
            new Section("connectivity", "0.4.0", null,
                    DesiredConfig::getConnectivity, (d, v) -> d.setConnectivity((DesiredConnectivity) v)),
            new Section("appPermissions", "0.5.0", DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY,
                    DesiredConfig::getAppPermissions, (d, v) -> d.setAppPermissions((List<DesiredAppPermissions>) v))
    ));

    /** MAJOR.MINOR[.PATCH], an optional leading v, then anything after a - or + (0.6.0-rc1, 0.5.1+fork, 0.5.1-myfork). */
    private static final Pattern VERSION = Pattern.compile("[vV]?(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?(?:[-+].*)?");

    private ConfigSections() {}

    /**
     * The sections of {@code doc} (not null) this device is not sent: a section with a capability its agent doesn't
     * advertise. Never decided by the agent's version.
     */
    public static List<Section> withheld(DesiredConfig doc, Set<String> deviceTokens) {
        Set<String> tokens = tokensOf(deviceTokens);
        List<Section> out = new ArrayList<Section>();
        if (doc == null) return out;
        for (Section s : ALL) {
            if (s.valueIn(doc) != null && s.getCapability() != null && !tokens.contains(s.getCapability())) out.add(s);
        }
        return out;
    }

    /**
     * The sections of {@code doc} that ask something (not {@link #isEmpty}) and this device doesn't apply, in
     * {@link #ALL} order: the device needs an agent update for each.
     *
     * @param agentVersion the version of the agent that applied the configuration; null, unreadable or below
     *                     0.3.0 (the first config.apply release) counts as applying every section
     */
    public static List<Section> notApplied(DesiredConfig doc, Set<String> deviceTokens, String agentVersion) {
        Set<String> tokens = tokensOf(deviceTokens);
        List<Section> out = new ArrayList<Section>();
        if (doc == null) return out;
        boolean lists = listsSections(tokens);
        // Below the first config.apply release reads as a version, but such an agent couldn't apply configurations: a
        // build without a release version (a development or fork build), so it counts as unreadable.
        String version = isBelow(agentVersion, FIRST_CONFIG_APPLY) ? null : agentVersion;
        for (Section s : ALL) {
            if (isEmpty(s.valueIn(doc))) continue;
            boolean applies = s.getCapability() == null || tokens.contains(s.getCapability());
            if (lists) applies &= tokens.contains(s.getToken());
            else if (s.getCapability() == null) applies = !isBelow(version, s.getSince());
            if (!applies) out.add(s);
        }
        return out;
    }

    private static Set<String> tokensOf(Set<String> deviceTokens) {
        return deviceTokens == null ? Collections.<String>emptySet() : deviceTokens;
    }

    /** Clears {@code sections} in {@code doc} and sets the revision of what is left. */
    public static void strip(DesiredConfig doc, List<Section> sections) {
        if (sections.isEmpty()) return;
        for (Section s : sections) s.setIn(doc, null);
        doc.setRevision(DesiredConfigBuilder.revision(doc));
    }

    /**
     * The revision of {@code doc} without {@code sections}, leaving {@code doc} as it was (it may be shared, as the
     * sync summary's per-configuration document is).
     */
    public static String revisionWithout(DesiredConfig doc, List<Section> sections) {
        if (sections.isEmpty()) return doc.getRevision();
        Object[] kept = new Object[sections.size()];
        try {
            for (int i = 0; i < kept.length; i++) {
                kept[i] = sections.get(i).valueIn(doc);
                sections.get(i).setIn(doc, null);
            }
            return DesiredConfigBuilder.revision(doc);
        } finally {
            for (int i = 0; i < kept.length; i++) sections.get(i).setIn(doc, kept[i]);
        }
    }

    /** True when the value asks nothing of a device: absent, or an empty list or map. */
    public static boolean isEmpty(Object value) {
        return value == null || (value instanceof Collection && ((Collection<?>) value).isEmpty())
                || (value instanceof Map && ((Map<?, ?>) value).isEmpty());
    }

    /** An agent from 0.6.0: it advertises the sections it applies. */
    private static boolean listsSections(Set<String> tokens) {
        for (String t : tokens) if (t.startsWith(TOKEN_PREFIX)) return true;
        return false;
    }

    /**
     * True only when {@code version} reads as a version and its MAJOR.MINOR.PATCH is below {@code since}'s. A
     * pre-release or build suffix is ignored (0.4.0-rc1 counts as 0.4.0): on doubt the section is sent.
     */
    static boolean isBelow(String version, String since) {
        int[] v = parse(version);
        int[] s = parse(since);
        if (v == null || s == null) return false;
        for (int i = 0; i < 3; i++) {
            if (v[i] != s[i]) return v[i] < s[i];
        }
        return false;
    }

    private static int[] parse(String version) {
        if (version == null) return null;
        Matcher m = VERSION.matcher(version.trim());
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }
}
