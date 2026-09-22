package com.hmdm.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.RequestUpdatesType;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredKiosk;
import com.hmdm.rest.json.agent.DesiredKioskFeatures;
import com.hmdm.rest.json.agent.DesiredKioskTheme;
import com.hmdm.rest.json.agent.DesiredLocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The ONLY place a {@link Configuration} row becomes a {@code config.apply} desired-state document.
 * Pure and deterministic: same inputs -&gt; same canonical JSON -&gt; same revision, on every server node,
 * so a device's reported {@code appliedConfigRevision} can be compared without storing anything.
 */
public final class DesiredConfigBuilder {
    public static final String COMMAND_TYPE = "config.apply";
    public static final String CAPABILITY = "device.configApply";
    public static final String POLICY_PREFIX = "policies.";
    public static final String KEY_KIOSK = "kiosk";
    public static final String KEY_LOCATION = "location";
    private static final int ACTION_INSTALL = 1;

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private DesiredConfigBuilder() {}

    public static DesiredConfig build(Configuration cfg, List<Application> apps) {
        DesiredConfig d = new DesiredConfig();
        d.setConfigurationId(cfg.getId());
        d.setPolicies(policies(cfg));
        d.setKiosk(cfg.isKioskMode() ? kiosk(cfg, apps == null ? Collections.<Application>emptyList() : apps) : null);
        DesiredLocation loc = new DesiredLocation();
        loc.setMode(cfg.getRequestUpdates() == RequestUpdatesType.GPS ? "active" : "passive");
        d.setLocation(loc);
        d.setRevision(revision(d));
        return d;
    }

    private static Map<String, Boolean> policies(Configuration cfg) {
        Map<String, Boolean> p = new TreeMap<String, Boolean>();
        putIfManaged(p, "wifi", cfg.getWifi());
        putIfManaged(p, "bluetooth", cfg.getBluetooth());
        putIfManaged(p, "usbStorage", cfg.getUsbStorage());
        putIfManaged(p, "screenshots", cfg.getDisableScreenshots() == null ? null : !cfg.getDisableScreenshots());
        return p;
    }

    private static void putIfManaged(Map<String, Boolean> p, String key, Boolean v) {
        if (v != null) p.put(key, v);
    }

    private static DesiredKiosk kiosk(Configuration cfg, List<Application> apps) {
        String mainPkg = null;
        List<String> allowed = new ArrayList<String>();
        for (Application a : apps) {
            if (a == null || a.getPkg() == null || a.getPkg().trim().isEmpty() || a.getAction() != ACTION_INSTALL) continue;
            String pkg = a.getPkg().trim();
            if (cfg.getMainAppId() != null && cfg.getMainAppId().equals(a.getId())) mainPkg = pkg;
            else if (!allowed.contains(pkg)) allowed.add(pkg);
        }
        Collections.sort(allowed);
        if (mainPkg != null) allowed.add(0, mainPkg);

        DesiredKiosk k = new DesiredKiosk();
        k.setMode(mainPkg != null && allowed.size() == 1 ? "single" : "launcher");
        k.setAllowedPackages(allowed);
        k.setPinPackage(mainPkg);
        DesiredKioskFeatures f = new DesiredKioskFeatures();
        f.setHome(cfg.getKioskHome()); f.setRecents(cfg.getKioskRecents()); f.setNotifications(cfg.getKioskNotifications());
        f.setSystemInfo(cfg.getKioskSystemInfo()); f.setKeyguard(cfg.getKioskKeyguard()); f.setLockButtons(cfg.getKioskLockButtons());
        k.setFeatures(f);
        k.setExitMode(Boolean.TRUE.equals(cfg.getKioskExit()) ? "visible" : "gesture");
        k.setPassword(cfg.getPassword());
        DesiredKioskTheme t = new DesiredKioskTheme();
        t.setBackgroundColor(cfg.getBackgroundColor()); t.setTextColor(cfg.getTextColor());
        t.setIconSize(cfg.getIconSize() == null ? null : cfg.getIconSize().name());
        k.setTheme(t);
        return k;
    }

    /** Recursively sorts map keys and drops null values so nested objects canonicalise too. */
    private static Object sorted(Object v) {
        if (v instanceof Map) {
            TreeMap<String, Object> m = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getValue() != null) m.put(String.valueOf(e.getKey()), sorted(e.getValue()));
            }
            return m;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<Object>();
            for (Object o : (List<?>) v) l.add(sorted(o));
            return l;
        }
        return v;
    }

    /** Canonical JSON of the document WITHOUT the revision field. */
    public static String canonicalJson(DesiredConfig doc) {
        try {
            Map<?, ?> asMap = PLAIN.convertValue(doc, Map.class);
            asMap.remove("revision");
            return PLAIN.writeValueAsString(sorted(asMap));
        } catch (Exception e) {
            throw new IllegalStateException("cannot canonicalise desired config", e);
        }
    }

    public static String revision(DesiredConfig doc) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canonicalJson(doc).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Full JSON (with revision) - the {@code agentCommand.payload} string. */
    public static String toPayloadJson(DesiredConfig doc) {
        try { return PLAIN.writeValueAsString(doc); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
