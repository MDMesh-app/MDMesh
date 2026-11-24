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
 * {@code configurationId}), one row each, as {@code proto/config-sections.json} lists them, and the one rule that
 * decides which of them a device gets.
 *
 * <p>An agent from 0.6.0 advertises {@code configSections}, flattened to {@code config.<name>}: it gets exactly the
 * sections it lists. An older agent advertises none: it gets a section that has a capability when it advertises that
 * capability, and any other section unless it reports a version below the section's {@code since}. Only a version that
 * reads as one counts: a missing or unreadable version gets every such section. A section left out is released by the agent (absent means "not managed"), so a guess
 * that strips one would release it on devices that apply it.</p>
 */
public final class ConfigSections {

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
         * The capability an agent before 0.6.0 advertises when it applies this section, which then decides alone (its
         * version doesn't count); null when none.
         */
        public String getCapability() { return capability; }

        /** The capability token an agent from 0.6.0 advertises when it applies this section. */
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
     * Whether a device with these capability tokens and this reported agent version gets {@code section}.
     *
     * @param agentVersion the version the agent reports; null or unreadable never withholds a section
     */
    public static boolean deviceTakes(Section section, Set<String> deviceTokens, String agentVersion) {
        Set<String> tokens = deviceTokens == null ? Collections.<String>emptySet() : deviceTokens;
        if (tokens.contains(section.getToken())) return true;
        if (listsSections(tokens)) return false;
        if (section.getCapability() != null) return tokens.contains(section.getCapability());
        return !isBelow(agentVersion, section.getSince());
    }

    /** The sections {@code doc} carries (not null) that the device doesn't get, in {@link #ALL} order. */
    public static List<Section> withheld(DesiredConfig doc, Set<String> deviceTokens, String agentVersion) {
        List<Section> out = new ArrayList<Section>();
        if (doc == null) return out;
        for (Section s : ALL) {
            if (s.valueIn(doc) != null && !deviceTakes(s, deviceTokens, agentVersion)) out.add(s);
        }
        return out;
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
