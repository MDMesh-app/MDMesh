package com.mdmesh.policies.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A phone's brand as one key, exactly as the console works it out (web/src/extensions/policies/model.ts: normalizeBrand
 * and deviceBrand), so a schedule aimed at a group reaches the phones the Groups page shows in it: the brand set on the
 * phone by hand, else the one its agent reports (Build.BRAND, then MANUFACTURER), else the first word of its name when
 * that is a known brand, else "unknown".
 */
final class BrandRules {
    static final String UNKNOWN = "unknown";

    /** Brands a phone's name may start with ("oppo A5x 5g Sunny"), for phones that never sent telemetry. */
    static final Set<String> KNOWN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "oppo", "realme", "oneplus", "vivo", "iqoo", "samsung", "xiaomi", "redmi", "poco", "motorola", "google",
            "nokia", "huawei", "honor", "lenovo", "infinix", "tecno", "itel", "lava", "nothing", "sony", "lg", "asus",
            "zebra")));

    /** Other spellings phones report, to one key. */
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("moto", "motorola");
        m.put("motorola mobility", "motorola");
        m.put("hmd global", "nokia");
        m.put("hmd", "nokia");
        m.put("one plus", "oneplus");
        m.put("mi", "xiaomi");
        m.put("samsung electronics", "samsung");
        ALIASES = Collections.unmodifiableMap(m);
    }

    private BrandRules() {
    }

    /** {@code OPPO} → {@code oppo}, {@code moto} → {@code motorola}; blank → unknown. */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (s.isEmpty()) return UNKNOWN;
        return ALIASES.getOrDefault(s, s);
    }

    /** The phone's brand: set by hand, else reported, else guessed from its name, else unknown. */
    static String of(String setByHand, String reportedBrand, String reportedManufacturer, String description) {
        if (setByHand != null && !setByHand.trim().isEmpty()) return setByHand.trim();
        if (reportedBrand != null && !reportedBrand.trim().isEmpty()) return normalize(reportedBrand);
        if (reportedManufacturer != null && !reportedManufacturer.trim().isEmpty()) return normalize(reportedManufacturer);
        String first = normalize((description == null ? "" : description).split("[\\s\\-_/(]+", 2)[0]);
        return KNOWN.contains(first) ? first : UNKNOWN;
    }
}
