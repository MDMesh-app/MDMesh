package com.hmdm.util;

import com.hmdm.rest.json.agent.DesiredConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The sections of a {@code config.apply} document (its top-level keys besides {@code revision} and
 * {@code configurationId}), one row each, as {@code proto/config-sections.json} lists them.
 *
 * <p>Leaving a section out can release it on the device (it does for kiosk). So a section is withheld from a device only
 * when it has a capability and the device's agent doesn't advertise it. Every other section is always sent: an agent
 * ignores one it doesn't know. Nothing else a device reports, its version least of all, withholds a section.</p>
 */
public final class ConfigSections {

    /** One section: its key in the document, the capability it needs (null: none) and its field. */
    public static final class Section<T> {
        final String name;
        final String capability;
        private final Function<DesiredConfig, T> get;
        private final BiConsumer<DesiredConfig, T> set;

        private Section(String name, String capability, Function<DesiredConfig, T> get, BiConsumer<DesiredConfig, T> set) {
            this.name = name;
            this.capability = capability;
            this.get = get;
            this.set = set;
        }

        /** True when the document asks nothing of a device here: no section, or an empty list. */
        public boolean isEmpty(DesiredConfig doc) {
            T value = get.apply(doc);
            return value == null || (value instanceof Collection && ((Collection<?>) value).isEmpty());
        }

        public void clear(DesiredConfig doc) { set.accept(doc, null); }

        /** Clears the section; the result puts it back. */
        private Runnable take(DesiredConfig doc) {
            T value = get.apply(doc);
            clear(doc);
            return () -> set.accept(doc, value);
        }
    }

    /** In the order agents apply them. A new section appends its row, here and in proto/config-sections.json. */
    static final List<Section<?>> ALL = Collections.unmodifiableList(Arrays.<Section<?>>asList(
            new Section<>("policies", null, DesiredConfig::getPolicies, DesiredConfig::setPolicies),
            new Section<>(DesiredConfigBuilder.KEY_KIOSK, null, DesiredConfig::getKiosk, DesiredConfig::setKiosk),
            new Section<>(DesiredConfigBuilder.KEY_LOCATION, null, DesiredConfig::getLocation, DesiredConfig::setLocation),
            new Section<>("connectivity", null, DesiredConfig::getConnectivity, DesiredConfig::setConnectivity),
            new Section<>("appPermissions", DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY,
                    DesiredConfig::getAppPermissions, DesiredConfig::setAppPermissions)));

    private ConfigSections() {}

    /** The sections {@code doc} has that a device with these capabilities is not sent. */
    public static List<Section<?>> withheld(DesiredConfig doc, Set<String> deviceTokens) {
        List<Section<?>> out = new ArrayList<>();
        for (Section<?> s : ALL) {
            if (s.get.apply(doc) != null && !AgentCapabilityTokens.isAllowed(s.capability, deviceTokens)) out.add(s);
        }
        return out;
    }

    /** The revision of {@code doc} without {@code sections}; {@code doc} is left as it was. */
    public static String revisionWithout(DesiredConfig doc, List<Section<?>> sections) {
        if (sections.isEmpty()) return doc.getRevision();
        List<Runnable> restore = new ArrayList<>();
        try {
            for (Section<?> s : sections) restore.add(s.take(doc));
            return DesiredConfigBuilder.revision(doc);
        } finally {
            for (Runnable r : restore) r.run();
        }
    }
}
