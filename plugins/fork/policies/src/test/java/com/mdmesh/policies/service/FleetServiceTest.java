package com.mdmesh.policies.service;

import com.hmdm.rest.json.agent.ConfigStatusView;
import com.mdmesh.policies.persistence.domain.FleetRow;
import com.mdmesh.policies.persistence.domain.OpenCommandRow;
import com.mdmesh.policies.rest.json.FleetView;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FleetServiceTest {
    private static final String CAPS = "{\"device\":[\"configApply\"],\"appManagement\":[\"runtimePermissions\"]}";
    private static final String CAPS_LITE = "{\"device\":[\"configApply\"]}";

    private final CoreStatus core = new CoreStatus(type -> new FakeReconciler(), FakeReconciler.class.getName());
    private final FleetService service = new FleetService(null, core);

    @Before
    public void reset() {
        FakeReconciler.documents = 0;
    }

    private static FleetRow row(int id, String number, Integer cfg, String caps, String applied) {
        FleetRow r = new FleetRow();
        r.setId(id);
        r.setNumber(number);
        r.setConfigurationId(cfg);
        r.setCapabilities(caps);
        r.setAppliedRevision(applied);
        return r;
    }

    private static OpenCommandRow open(String number, String type, String payload) {
        OpenCommandRow c = new OpenCommandRow();
        c.setDeviceNumber(number);
        c.setType(type);
        c.setPayload(payload);
        return c;
    }

    @Test
    public void statusMatchesCoreRules() {
        FleetRow applied = row(1, "P-1", 4, CAPS, "rev-4");
        applied.setApplyId(10);
        applied.setApplyStatus("done");
        applied.setApplyDetail("{\"outcomes\":{\"policies.camera\":\"unsupported\"}}");
        FleetRow waiting = row(2, "P-2", 4, CAPS, "old");
        FleetRow lite = row(3, "P-3", 4, CAPS_LITE, "rev-4-lite");
        FleetRow oldAgent = row(4, "P-4", 4, null, null);
        FleetRow noConfig = row(5, "P-5", null, CAPS, null);

        FleetView v = service.build(1, Arrays.asList(applied, waiting, lite, oldAgent, noConfig), Collections.<OpenCommandRow>emptyList(), 7L);

        assertTrue(v.statusAvailable);
        assertEquals(7L, v.serverTime);
        ConfigStatusView s1 = v.devices.get(0).status;
        assertTrue(s1.isInSync());
        assertTrue(s1.isSupported());
        assertEquals("done", s1.getLastCommand().getStatus());
        assertEquals("{\"outcomes\":{\"policies.camera\":\"unsupported\"}}", s1.getLastCommand().getDetail());
        assertFalse(v.devices.get(1).status.isInSync());
        assertNull(v.devices.get(1).status.getLastCommand());
        // A phone without app-permission support is compared with what it can apply.
        assertTrue(v.devices.get(2).status.isInSync());
        assertFalse(v.devices.get(3).status.isSupported());
        assertNull(v.devices.get(4).status.getCurrentRevision());
        // One document per configuration, not per phone.
        assertEquals(1, FakeReconciler.documents);
    }

    @Test
    public void aConfigurationCoreCannotBuildLeavesOnlyItsPhonesToCore() {
        FleetView v = service.build(1, Arrays.asList(row(1, "P-1", 99, CAPS, "x"), row(2, "P-2", 99, CAPS, "x"),
                row(3, "P-3", 4, CAPS, "rev-4")), Collections.<OpenCommandRow>emptyList(), 0L);
        assertNull(v.devices.get(0).status);
        assertNull(v.devices.get(1).status);
        assertTrue(v.devices.get(2).status.isInSync());
        // The broken configuration is tried once, not once per phone.
        assertEquals(2, FakeReconciler.documents);
    }

    @Test
    public void withoutCoreStatusEveryStatusIsLeftToCore() {
        CoreStatus missing = new CoreStatus(type -> new Object(), "com.example.NoSuchClass");
        assertFalse(missing.available());
        FleetView v = new FleetService(null, missing).build(1, Collections.singletonList(row(1, "P-1", 4, CAPS, "rev-4")),
                Collections.<OpenCommandRow>emptyList(), 0L);
        assertFalse(v.statusAvailable);
        assertNull(v.devices.get(0).status);
        assertEquals("P-1", v.devices.get(0).number);
    }

    @Test
    public void readsBrandScanAndOpenCommands() {
        FleetRow r = row(1, "P-1", 4, CAPS, "rev-4");
        r.setTelemetry("{\"dynamic\":{},\"hardware\":{\"brand\":\"OPPO\",\"manufacturer\":\"OPPO\",\"model\":\"A5\"}}");
        r.setScanDetail("{\"apps\":[{\"pkg\":\"com.whatsapp\",\"label\":\"WhatsApp\",\"launchable\":true,\"versionCode\":5},"
                + "{\"pkg\":\"com.android.chrome\",\"label\":\"Chrome\",\"system\":true},{\"label\":\"no package\"},{\"pkg\":7}]}");
        r.setScannedAt(123L);
        FleetView v = service.build(1, Collections.singletonList(r), Arrays.asList(
                open("P-1", "apps.scan", null),
                open("P-1", "app.uninstall", "{\"packageName\":\"com.truecaller\"}"),
                open("P-1", "app.uninstall", "{\"packageName\":\"com.truecaller\"}"),
                open("P-2", "app.uninstall", "{\"packageName\":\"com.other\"}")), 0L);
        FleetView.Phone p = v.devices.get(0);
        assertEquals("OPPO", p.hardware.get("brand"));
        assertEquals(2, p.hardware.size());
        assertNotNull(p.scan);
        assertEquals(Long.valueOf(123L), p.scan.scannedAt);
        assertEquals(2, p.scan.apps.size());
        assertEquals("com.whatsapp", p.scan.apps.get(0).pkg);
        assertNull(p.scan.apps.get(0).system);
        assertEquals(Boolean.TRUE, p.scan.apps.get(1).system);
        assertTrue(p.scanPending);
        assertEquals(Collections.singletonList("com.truecaller"), p.removing);
    }

    @Test
    public void reportsTheAgentVersionEachPhoneLastSent() {
        FleetRow newer = row(1, "P-1", 4, CAPS, "rev-4");
        newer.setAgentVersion("0.5.1");
        FleetRow older = row(2, "P-2", 4, CAPS, "rev-4");
        older.setAgentVersion("0.3.4");
        FleetView v = service.build(1, Arrays.asList(newer, older), Collections.<OpenCommandRow>emptyList(), 0L);
        assertEquals("0.5.1", v.devices.get(0).agentVersion);
        assertEquals("0.3.4", v.devices.get(1).agentVersion);
    }

    @Test
    public void unreadableDataIsSkippedNotFatal() {
        FleetRow r = row(1, "P-1", 4, "not json", "rev-4");
        r.setTelemetry("{broken");
        r.setScanDetail("[]");
        FleetView v = service.build(1, Collections.singletonList(r), Collections.<OpenCommandRow>emptyList(), 0L);
        FleetView.Phone p = v.devices.get(0);
        assertNull(p.hardware);
        assertNull(p.scan);
        assertFalse(p.status.isSupported());
    }
}
