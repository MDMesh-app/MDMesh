package com.hmdm.rest.resource.support;

import com.hmdm.persistence.RolloutDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Check-in re-queue of a staged agent update (#74), against a fake DAO (Mockito 1.x cannot proxy on JDK 17). */
public class RolloutRequeuerTest {

    private static final String PKG = "com.mdmesh.agent";
    private static final long CREATED = 1_000_000L;
    private static final String CAPS = "{\"appManagement\":[\"silentInstall\"]}";

    /** A RolloutDAO answering from fields and recording which queries ran. */
    private static final class FakeRolloutDAO extends RolloutDAO {
        AgentRollout active;
        boolean canary = true;
        RolloutDeviceRow row;
        List<AgentCommand> installs = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        FakeRolloutDAO() { super(null); }

        @Override public AgentRollout findActiveByCustomer(int customerId) { calls.add("active"); return active; }
        @Override public boolean isCanary(int rolloutId, String deviceNumber) { calls.add("canary"); return canary; }
        @Override public RolloutDeviceRow findDeviceRow(String deviceNumber) { calls.add("row"); return row; }
        @Override public List<AgentCommand> listInstallsSince(String deviceNumber, long since) {
            calls.add("installs:" + deviceNumber + "@" + since);
            return installs;
        }
        @Override public boolean insertIfNotInFlight(AgentCommand cmd) {
            if (alreadyInFlight) return false;
            inserted.add(cmd);
            return true;
        }
        boolean alreadyInFlight;
        final List<AgentCommand> inserted = new ArrayList<>();
    }

    private FakeRolloutDAO rolloutDAO;
    private RolloutRequeuer requeuer;
    private AgentRollout rollout;
    private Device device;
    private List<AgentCommand> inserted;

    @Before
    public void setUp() {
        rolloutDAO = new FakeRolloutDAO();
        requeuer = new RolloutRequeuer(rolloutDAO);
        inserted = rolloutDAO.inserted;

        rollout = new AgentRollout();
        rollout.setId(7);
        rollout.setCustomerId(1);
        rollout.setTargetVersion("0.3.3");
        rollout.setPackageName(PKG);
        rollout.setApkUrl("https://mdm.example.com/update/agent.apk?v=303");
        rollout.setStage("canary");
        rollout.setCreatedAt(CREATED);
        rolloutDAO.active = rollout;
        rolloutDAO.row = row("0.3.1", CAPS);

        device = new Device();
        device.setNumber("d1");
        device.setCustomerId(1);
    }

    private static RolloutDeviceRow row(String version, String caps) {
        RolloutDeviceRow r = new RolloutDeviceRow();
        r.setDeviceNumber("d1");
        r.setAgentVersion(version);
        r.setCapabilitiesJson(caps);
        return r;
    }

    private static AgentCommand install(String pkg, String status, long createdAt) {
        AgentCommand c = new AgentCommand();
        c.setDeviceNumber("d1");
        c.setType("app.install");
        c.setPayload("{\"url\":\"https://x/a.apk\",\"packageName\":\"" + pkg + "\"}");
        c.setStatus(status);
        c.setCreatedAt(createdAt);
        c.setDeliveredAt("pending".equals(status) ? null : createdAt + 1);
        return c;
    }

    private static AgentCommand undelivered(AgentCommand c) {
        c.setDeliveredAt(null);
        return c;
    }

    /** Newest first, as the DAO returns them. */
    private void installs(AgentCommand... newestFirst) {
        rolloutDAO.installs = Arrays.asList(newestFirst);
    }

    @Test
    public void expiredIsQueuedAgain() {
        installs(install(PKG, "expired", CREATED + 10));
        assertTrue(requeuer.requeue(device, CREATED + 100));
        assertEquals(1, inserted.size());
        assertTrue(inserted.get(0).getPayload().contains("\"packageName\":\"" + PKG + "\""));
        assertEquals("pending", inserted.get(0).getStatus());
    }

    @Test
    public void neverQueuedIsQueued() {
        installs(install("org.fdroid.fdroid", "pending", CREATED + 10)); // an unrelated install does not count
        assertTrue(requeuer.requeue(device, CREATED + 100));
        assertEquals(1, inserted.size());
    }

    @Test
    public void failedIsRetriedUpToThreeAttempts() {
        installs(install(PKG, "failed", CREATED + 20), install(PKG, "failed", CREATED + 10));
        assertTrue(requeuer.requeue(device, CREATED + 100));
        assertEquals(1, inserted.size());
    }

    @Test
    public void failedThreeTimesIsNotRetried() {
        installs(install(PKG, "failed", CREATED + 30), install(PKG, "expired", CREATED + 20),
                install(PKG, "failed", CREATED + 10));
        assertFalse(requeuer.requeue(device, CREATED + 100));
        assertTrue(inserted.isEmpty());
    }

    @Test
    public void inFlightIsNotQueuedAgain() {
        installs(install(PKG, "delivered", CREATED + 20), install(PKG, "expired", CREATED + 10));
        assertFalse(requeuer.requeue(device, CREATED + 100));
        assertTrue(inserted.isEmpty());
    }

    @Test
    public void deviceOutsideTheCanaryIsSkippedDuringCanary() {
        rolloutDAO.canary = false;
        installs();
        assertFalse(requeuer.requeue(device, CREATED + 100));
        assertTrue(inserted.isEmpty());
    }

    @Test
    public void fleetStageCoversEveryDevice() {
        rollout.setStage("fleet");
        rolloutDAO.canary = false;
        installs();
        assertTrue(requeuer.requeue(device, CREATED + 100));
    }

    @Test
    public void onlyCommandsFromThisRolloutCount() {
        // The DAO is asked only for commands since the rollout was created: three failures from an earlier
        // rollout (before CREATED) never reach the decision.
        installs();
        assertTrue(requeuer.requeue(device, CREATED + 100));
        assertTrue(rolloutDAO.calls.contains("installs:d1@" + CREATED));
    }

    @Test
    public void updatedOrIneligibleDevicesSkipTheInstallQuery() {
        rolloutDAO.row = row("0.3.3", CAPS);
        assertFalse(requeuer.requeue(device, CREATED + 100));
        rolloutDAO.row = row("0.3.1", "{}");
        assertFalse(requeuer.requeue(device, CREATED + 100));
        assertFalse(rolloutDAO.calls.stream().anyMatch(c -> c.startsWith("installs:")));
        assertTrue(inserted.isEmpty());
    }

    @Test
    public void noActiveRolloutCostsOneQuery() {
        rolloutDAO.active = null;
        assertFalse(requeuer.requeue(device, CREATED + 100));
        assertEquals(Collections.singletonList("active"), rolloutDAO.calls);
        assertEquals(Collections.emptyList(), inserted);
    }

    @Test
    public void deliveredExpiriesCountTowardTheCap() {
        // Installed each time, yet the device never reports the target version: stop after three.
        installs(install(PKG, "expired", CREATED + 30), install(PKG, "expired", CREATED + 20),
                install(PKG, "expired", CREATED + 10));
        assertFalse(requeuer.requeue(device, CREATED + 100));
        assertTrue(inserted.isEmpty());
    }

    @Test
    public void undeliveredExpiriesAreNotCapped() {
        // The device was offline each time: none of these was an attempt.
        installs(undelivered(install(PKG, "expired", CREATED + 30)), undelivered(install(PKG, "expired", CREATED + 20)),
                undelivered(install(PKG, "expired", CREATED + 10)), undelivered(install(PKG, "expired", CREATED + 5)));
        assertTrue(requeuer.requeue(device, CREATED + 100));
        assertEquals(1, inserted.size());
    }

    @Test
    public void aCommandQueuedConcurrentlyIsNotDuplicated() {
        // create/promote queued it between this check-in's read and its insert.
        installs();
        rolloutDAO.alreadyInFlight = true;
        assertFalse(requeuer.requeue(device, CREATED + 100));
        assertTrue(inserted.isEmpty());
    }
}
