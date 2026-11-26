package com.mdmesh.policies.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The console's "extra apps" rule (web/src/extensions/policies/model.ts, findExtras), for the server's auto-remove:
 * apps of a phone's latest scan that are not allowed, not marked system by the phone, not the agent, not excepted for
 * that phone and not already being removed. Pure: no database, so it is tested on its own.
 */
public final class AutoRemoveRules {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The agent itself: never removed. Same list as the console's PROTECTED_PKGS. */
    static final Set<String> PROTECTED = new HashSet<>(java.util.Arrays.asList("com.mdmesh.agent", "com.hmdm.launcher"));

    private AutoRemoveRules() {
    }

    /** What a configuration's {@code extensions.policies} says about extra apps. */
    public static final class Rules {
        public final String mode;
        public final boolean notify;
        /** Phone number to the packages it may keep. */
        final Map<String, Set<String>> exceptions;

        Rules(String mode, boolean notify, Map<String, Set<String>> exceptions) {
            this.mode = mode;
            this.notify = notify;
            this.exceptions = exceptions;
        }

        public boolean autoRemove() {
            return "auto".equals(mode);
        }

        Set<String> exceptionsOf(String number) {
            Set<String> s = exceptions.get(number);
            return s == null ? Collections.<String>emptySet() : s;
        }
    }

    /** The policy JSON as rules; unreadable or missing data means report only (nothing is ever removed for it). */
    public static Rules parse(String policyJson) {
        JsonNode apps = read(policyJson).path("apps");
        String mode = apps.path("mode").isTextual() ? apps.path("mode").asText() : "report";
        boolean notify = !apps.path("notify").isBoolean() || apps.path("notify").asBoolean();
        Map<String, Set<String>> exceptions = new HashMap<>();
        for (JsonNode e : apps.path("exceptions")) {
            if (!e.path("pkg").isTextual() || !e.path("device").isTextual()) continue;
            String device = e.path("device").asText().trim();
            Set<String> s = exceptions.get(device);
            if (s == null) exceptions.put(device, s = new HashSet<>());
            s.add(e.path("pkg").asText().trim());
        }
        return new Rules(mode, notify, exceptions);
    }

    /** The phone's extra apps from its scan, package to label, in scan order. Empty unless the policy auto-removes. */
    public static Map<String, String> extras(String scanDetail, Set<String> allowed, Rules rules, String number,
                                             Set<String> removing) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rules == null || !rules.autoRemove()) return out;
        Set<String> kept = rules.exceptionsOf(number);
        for (JsonNode a : read(scanDetail).path("apps")) {
            JsonNode pkgNode = a.path("pkg");
            if (!pkgNode.isTextual() || pkgNode.asText().isEmpty()) continue;
            String pkg = pkgNode.asText();
            if (a.path("system").asBoolean(false) || allowed.contains(pkg) || kept.contains(pkg)
                    || PROTECTED.contains(pkg) || removing.contains(pkg)) continue;
            out.put(pkg, a.path("label").isTextual() && !a.path("label").asText().isEmpty() ? a.path("label").asText() : pkg);
        }
        return out;
    }

    /** The alert a removal shows, as the console words it (jobs.ts, removalAlert). */
    public static String[] alert(List<String> labels) {
        String body = String.join(", ", labels) + (labels.size() == 1 ? " is" : " are") + " not allowed on this phone.";
        return new String[]{"App removed by your administrator", body};
    }

    static List<String> labels(Map<String, String> extras) {
        return new ArrayList<>(extras.values());
    }

    private static JsonNode read(String json) {
        if (json == null || json.isEmpty()) return JSON.missingNode();
        try {
            JsonNode n = JSON.readTree(json);
            return n == null ? JSON.missingNode() : n;
        } catch (Exception e) {
            return JSON.missingNode();
        }
    }
}
