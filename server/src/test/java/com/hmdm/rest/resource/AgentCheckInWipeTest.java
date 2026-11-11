/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCheckInResponse;
import com.hmdm.rest.json.agent.AgentCommandResult;
import com.hmdm.rest.resource.support.ApkHashResolver;
import com.hmdm.rest.resource.support.AppReconciler;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskLockoutAlert;
import com.hmdm.rest.resource.support.RolloutRequeuer;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.CryptoUtil;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A wiped device erases itself before it can report, so delivering device.wipe is what marks it wiped. The mark comes
 * off when the device says the wipe failed, or when it keeps checking in well after the wipe was delivered.
 */
public class AgentCheckInWipeTest {

    private static final String SECRET = "s3cret";
    private static final String DEVICE = "dev-1";

    /** What the check-in finds queued, and the device's stored wipedAt. */
    private String pendingType;
    private Long wipedAt;

    private final List<String> calls = new ArrayList<>();
    private AgentResource resource;

    @Before
    public void setUp() throws Exception {
        UnsecureDAO unsecureDAO = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, "unused", 0, null) {
            @Override public Device getDeviceByNumber(String number) {
                if (!DEVICE.equals(number)) return null;
                Device d = new Device();
                d.setNumber(DEVICE);
                d.setCustomerId(1);
                d.setWipedAt(wipedAt);
                return d;
            }
        };
        AgentCommandDAO commands = new AgentCommandDAO(null, null, null, null) {
            @Override public String getDeviceSecretHash(String n) { return CryptoUtil.getSHA256String(SECRET); }
            @Override public void touchLastUpdate(String n) { }
            @Override public void expireStale(String n, long a, long b) { }
            @Override public void updateDeviceCapabilities(String n, String json) { }
            @Override public String getDeviceCapabilities(String n) { return null; }
            @Override public void markResultWithTime(String n, Integer id, String status, String detail, Long at) { }
            @Override public void recordConfigApplyDone(String n, Integer id) { }
            @Override public List<AgentCommand> listPending(String n) {
                if (pendingType == null) return Collections.emptyList();
                AgentCommand c = new AgentCommand();
                c.setId(9);
                c.setDeviceNumber(DEVICE);
                c.setType(pendingType);
                c.setStatus("pending");
                return Collections.singletonList(c);
            }
            @Override public boolean claimForDelivery(Integer id, Long at) { return true; }
            @Override public void markWiped(String n, long at) { calls.add("markWiped:" + n); }
            @Override public boolean clearWipedForCommand(String n, Integer id) {
                calls.add("clearWipedForCommand:" + id);
                return true;
            }
            @Override public boolean clearWipedBefore(String n, long cutoff) {
                calls.add("clearWipedBefore");
                return true;
            }
        };
        ConfigReconciler reconciler = new ConfigReconciler(null, null, Fakes.noExtensionPoints()) {
            @Override public boolean reconcile(Device d, Set<String> t, String rev, String agentVersion,
                                               String configAgentVersion, long now, long receivedAt) { return false; }
        };
        RolloutRequeuer requeuer = new RolloutRequeuer(null) {
            @Override public boolean requeue(Device d, long now) { return false; }
        };
        String files = Files.createTempDirectory("files").toString();
        InstallHashes hashes = new InstallHashes(new ApkHashResolver(files, "https://mdm.example.com"), unsecureDAO);
        resource = new AgentResource(unsecureDAO, null, commands,
                new ConfigAppInstaller(null, null, null, null), reconciler, new AppReconciler(null, null, null, null),
                requeuer, hashes, new KioskLockoutAlert(null, null, null));
    }

    private List<com.hmdm.rest.json.agent.AgentCommand> checkIn(AgentCommandResult... results) {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(DEVICE);
        if (results.length > 0) {
            List<AgentCommandResult> list = new ArrayList<>();
            Collections.addAll(list, results);
            req.setResults(list);
        }
        Response r = resource.checkin("Bearer " + SECRET, null, req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        return ((AgentCheckInResponse) r.getData()).getCommands();
    }

    private static AgentCommandResult result(String status) {
        AgentCommandResult res = new AgentCommandResult();
        res.setCommandId("9");
        res.setStatus(status);
        return res;
    }

    @Test
    public void deliveringAWipeMarksTheDeviceWiped() {
        pendingType = "device.wipe";
        assertEquals(1, checkIn().size());
        assertEquals(Collections.singletonList("markWiped:" + DEVICE), calls);
    }

    @Test
    public void deliveringAnotherCommandMarksNothing() {
        pendingType = "device.lock";
        assertEquals(1, checkIn().size());
        assertTrue(calls.toString(), calls.isEmpty());
    }

    @Test
    public void aFailedOrUnsupportedResultUnmarksTheWipedDevice() {
        wipedAt = System.currentTimeMillis() - 30_000L;
        checkIn(result("failed"));
        checkIn(result("unsupported"));
        assertEquals(java.util.Arrays.asList("clearWipedForCommand:9", "clearWipedForCommand:9"), calls);
    }

    @Test
    public void aDoneResultKeepsTheMark() {
        wipedAt = System.currentTimeMillis() - 30_000L;
        checkIn(result("done"));
        assertTrue(calls.toString(), calls.isEmpty());
    }

    @Test
    public void aFailedResultFromADeviceNotMarkedWipedTouchesNothing() {
        checkIn(result("failed"));
        assertTrue(calls.toString(), calls.isEmpty());
    }

    @Test
    public void aCheckInLongAfterTheWipeUnmarksTheDevice() {
        wipedAt = System.currentTimeMillis() - AgentResource.WIPE_GRACE_MS - 60_000L;
        checkIn();
        assertEquals(Collections.singletonList("clearWipedBefore"), calls);
    }

    @Test
    public void aCheckInRightAfterTheWipeKeepsTheMark() {
        // the agent may still check in once between receiving the wipe and the reset starting
        wipedAt = System.currentTimeMillis() - 30_000L;
        checkIn();
        assertTrue(calls.toString(), calls.isEmpty());
    }
}
