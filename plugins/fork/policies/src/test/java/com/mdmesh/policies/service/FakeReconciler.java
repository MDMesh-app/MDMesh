package com.mdmesh.policies.service;

import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.agent.DesiredConfig;

import java.util.Set;

/** Stands in for core's ConfigReconciler: the same two method signatures CoreStatus calls. */
public class FakeReconciler {
    public static int documents;

    /** Configuration 99 fails as a broken server plugin would; the others get revision "rev-&lt;id&gt;". */
    public DesiredConfig statusDocument(Device device) {
        documents++;
        if (device.getConfigurationId() == 99) throw new IllegalStateException("broken contributor");
        DesiredConfig doc = new DesiredConfig();
        doc.setRevision("rev-" + device.getConfigurationId());
        if (device.getConfigurationId() == 7) {
            // Configuration 7 has kiosk on, with the dialer allowed and an exit PIN.
            com.hmdm.rest.json.agent.DesiredKiosk k = new com.hmdm.rest.json.agent.DesiredKiosk();
            k.setMode("launcher");
            k.setAllowedPackages(java.util.Arrays.asList("com.android.dialer", "com.whatsapp"));
            k.setExitMode("gesture");
            k.setPassword("6053");
            doc.setKiosk(k);
        }
        return doc;
    }

    /** A phone without app-permission support gets a different revision, as in core. */
    public static String statusRevision(DesiredConfig doc, Set<String> deviceTokens) {
        if (doc == null) return null;
        return deviceTokens.contains("app.runtimePermissions") ? doc.getRevision() : doc.getRevision() + "-lite";
    }
}
