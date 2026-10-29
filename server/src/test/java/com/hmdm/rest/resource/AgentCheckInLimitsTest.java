/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCapabilities;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCommandResult;
import com.hmdm.rest.json.agent.AgentDeviceState;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskLockoutAlert;
import com.hmdm.rest.resource.support.RolloutRequeuer;
import com.hmdm.util.CryptoUtil;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** One authenticated check-in can only cause a bounded amount of database work and storage. */
public class AgentCheckInLimitsTest {

    private static final String SECRET = "s3cret";
    private static final String DEVICE = "dev-1";

    private static final class FakeUnsecureDAO extends UnsecureDAO {
        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, "unused", 0, null);
        }
        @Override public Device getDeviceByNumber(String number) {
            if (!DEVICE.equals(number)) return null;
            Device d = new Device();
            d.setNumber(DEVICE);
            d.setCustomerId(1);
            return d;
        }
    }

    private static final class FakeCommandDAO extends AgentCommandDAO {
        final List<String> details = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<String> storedCaps = new ArrayList<>();
        final List<DeviceLocation> locations = new ArrayList<>();
        String telemetry;
        int capabilityReads;
        FakeCommandDAO() { super(null, null, null, null); }
        @Override public String getDeviceSecretHash(String n) { return CryptoUtil.getSHA256String(SECRET); }
        @Override public void touchLastUpdate(String n) { }
        @Override public void markResultWithTime(String n, Integer id, String status, String detail, Long at) {
            details.add(detail);
            statuses.add(status);
        }
        @Override public void expireStale(String n, long a, long b) { }
        @Override public void recordConfigApplyDone(String n, Integer id) { }
        @Override public void updateDeviceCapabilities(String n, String json) { storedCaps.add(json); }
        @Override public String getDeviceCapabilities(String n) {
            capabilityReads++;
            return "{\"device\":[\"lock\"]}";
        }
        @Override public List<AgentCommand> listPending(String n) { return Collections.emptyList(); }
        @Override public void upsertState(DeviceState s) { telemetry = s.getTelemetry(); }
        @Override public void updateAndroidVersion(String n, String v) { }
        @Override public void recordLocation(DeviceLocation l) { locations.add(l); }
    }

    private static final class NoopReconciler extends ConfigReconciler {
        Set<String> tokens;
        NoopReconciler() { super(null, null); }
        @Override public boolean reconcile(Device d, Set<String> t, String rev, String agentVersion,
                                           String configAgentVersion, long now, long receivedAt) { tokens = t; return false; }
    }

    private static final class NoopRequeuer extends RolloutRequeuer {
        NoopRequeuer() { super(null); }
        @Override public boolean requeue(Device d, long now) { return false; }
    }

    private FakeCommandDAO commands;
    private NoopReconciler reconciler;
    private AgentResource resource;

    @Before
    public void setUp() {
        commands = new FakeCommandDAO();
        reconciler = new NoopReconciler();
        resource = new AgentResource(new FakeUnsecureDAO(), null, commands,
                new ConfigAppInstaller(null, null, null, null), reconciler, new NoopRequeuer(),
                new InstallHashes(null, null), new KioskLockoutAlert(null, null, null));
    }

    private Response checkin(AgentCheckInRequest req) {
        req.setDeviceId(DEVICE);
        return resource.checkin("Bearer " + SECRET, null, req);
    }

    private static AgentCommandResult result(int id, String detail) {
        AgentCommandResult r = new AgentCommandResult();
        r.setCommandId(String.valueOf(id));
        r.setStatus("done");
        r.setDetail(detail);
        return r;
    }

    private static int accepted(Response r) {
        return ((com.hmdm.rest.json.agent.AgentCheckInResponse) r.getData()).getResultsAccepted();
    }

    @Test
    public void capsTheResultsAppliedPerCheckInAndTellsTheAgentHowManyWereTaken() {
        List<AgentCommandResult> results = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            results.add(result(i, null));
        }
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        Response r = checkin(req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(AgentResource.MAX_RESULTS_PER_CHECKIN, commands.details.size());
        // The agent keeps (and resends) everything past this count instead of losing it.
        assertEquals(AgentResource.MAX_RESULTS_PER_CHECKIN, accepted(r));
    }

    @Test
    public void reportsEveryResultAcceptedWhenTheyFit() {
        List<AgentCommandResult> results = new ArrayList<>();
        results.add(null);
        results.add(result(1, null));
        results.add(result(2, "x"));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        Response r = checkin(req);
        assertEquals(3, accepted(r));
        assertEquals(2, commands.details.size());
    }

    @Test
    public void reportsZeroAcceptedWithoutResults() {
        assertEquals(0, accepted(checkin(new AgentCheckInRequest())));
    }

    @Test
    public void invalidEntriesCountTowardsTheCap() {
        List<AgentCommandResult> results = new ArrayList<>();
        for (int i = 0; i < AgentResource.MAX_RESULTS_PER_CHECKIN; i++) {
            results.add(null);
        }
        results.add(result(1, null));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        assertEquals(AgentResource.MAX_RESULTS_PER_CHECKIN, accepted(checkin(req)));
        assertEquals(0, commands.details.size());
    }

    @Test
    public void storesAnOversizedResultAsAShortFailure() {
        StringBuilder big = new StringBuilder();
        while (big.length() <= AgentResource.MAX_RESULT_DETAIL_CHARS) {
            big.append("0123456789abcdef");
        }
        List<AgentCommandResult> results = new ArrayList<>();
        results.add(result(1, big.toString()));
        results.add(result(2, "installed"));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        checkin(req);
        assertEquals("failed", commands.statuses.get(0));
        assertTrue(commands.details.get(0), commands.details.get(0).startsWith("result too large"));
        assertTrue(commands.details.get(0).length() < 200);
        assertEquals("done", commands.statuses.get(1));
        assertEquals("installed", commands.details.get(1));
    }

    @Test
    public void ignoresAnOversizedCapabilityTreeAndGatesOnTheStoredOne() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode cur = root;
        for (int i = 0; i < 100; i++) {
            cur = cur.putObject("n");
        }
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setCapabilities(new AgentCapabilities(root));

        assertEquals(Response.ResponseStatus.OK, checkin(req).getStatus());
        assertTrue("an oversized tree must not be stored", commands.storedCaps.isEmpty());
        assertEquals(1, commands.capabilityReads);
        assertTrue(reconciler.tokens.contains("device.lock"));
    }

    @Test
    public void storesANormalCapabilityTree() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putArray("device").add("wipe");
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setCapabilities(new AgentCapabilities(root));

        checkin(req);
        assertEquals(Collections.singletonList(root.toString()), commands.storedCaps);
        assertEquals(0, commands.capabilityReads);
        assertTrue(reconciler.tokens.contains("device.wipe"));
    }

    private DeviceLocation checkInWithProvider(String provider) {
        ObjectNode tel = JsonNodeFactory.instance.objectNode();
        ObjectNode loc = tel.putObject("dynamic").putObject("location");
        loc.put("lat", 52.1);
        loc.put("lon", 4.3);
        loc.put("capturedAt", 1L);
        loc.set("provider", JsonNodeFactory.instance.textNode(provider));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setState(new AgentDeviceState());
        req.setTelemetry(tel);

        assertEquals(Response.ResponseStatus.OK, checkin(req).getStatus());
        assertEquals(1, commands.locations.size());
        return commands.locations.get(0);
    }

    @Test
    public void dropsAProviderThatIsNotAPlainNameAndKeepsTheFix() {
        DeviceLocation row = checkInWithProvider("<img src=x onerror=alert(1)>");
        assertNull(row.getProvider());
        assertEquals(52.1, row.getLat(), 0.0);
        assertEquals(4.3, row.getLon(), 0.0);
        assertFalse(commands.telemetry, commands.telemetry.contains("provider"));
        assertTrue(commands.telemetry, commands.telemetry.contains("\"lat\":52.1"));
    }

    @Test
    public void keepsAPlainProviderName() {
        assertEquals("fused", checkInWithProvider("fused").getProvider());
        assertTrue(commands.telemetry, commands.telemetry.contains("\"provider\":\"fused\""));
    }

    @Test
    public void dropsAnOverlongProviderButStillStoresTheRow() {
        String longName = "abcdefghijklmnopqrstuvwxyz0123456";
        assertEquals(33, longName.length());
        DeviceLocation row = checkInWithProvider(longName);
        assertNull(row.getProvider());
        assertEquals(52.1, row.getLat(), 0.0);
        assertFalse(commands.telemetry, commands.telemetry.contains("provider"));
    }
}
