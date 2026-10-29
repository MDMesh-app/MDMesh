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
import com.hmdm.rest.resource.support.ApkHashResolver;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskLockoutAlert;
import com.hmdm.rest.resource.support.RolloutRequeuer;
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

/** A queued app.install without a sha256 gets one at delivery; only a definite "no hash" ends it. */
public class AgentDeliveryHashTest {

    private static final String SECRET = "s3cret";
    private static final String DEVICE = "dev-1";
    private static final String EXTERNAL = "https://cdn.example.com/app.apk";
    private static final String HEX = "ab" + String.join("", Collections.nCopies(31, "cd"));

    /** What the stored-hash lookup does: answer {@link #storedHash}, or fail like a database outage. */
    private String storedHash;
    private boolean lookupFails;

    private final List<String> marked = new ArrayList<>();
    private final List<Integer> claimed = new ArrayList<>();
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
                return d;
            }
            @Override public String findApkHashByUrl(int customerId, String url) {
                if (lookupFails) throw new IllegalStateException("database unavailable");
                return storedHash;
            }
        };
        AgentCommandDAO commands = new AgentCommandDAO(null, null, null, null) {
            @Override public String getDeviceSecretHash(String n) { return CryptoUtil.getSHA256String(SECRET); }
            @Override public void touchLastUpdate(String n) { }
            @Override public void expireStale(String n, long a, long b) { }
            @Override public void updateDeviceCapabilities(String n, String json) { }
            @Override public String getDeviceCapabilities(String n) { return null; }
            @Override public void markResultWithTime(String n, Integer id, String status, String detail, Long at) {
                marked.add(id + ":" + status);
            }
            @Override public List<AgentCommand> listPending(String n) {
                AgentCommand c = new AgentCommand();
                c.setId(7);
                c.setDeviceNumber(DEVICE);
                c.setType("app.install");
                c.setPayload("{\"url\":\"" + EXTERNAL + "\",\"packageName\":\"com.example\"}");
                c.setStatus("pending");
                return Collections.singletonList(c);
            }
            @Override public boolean claimForDelivery(Integer id, Long at) { claimed.add(id); return true; }
        };
        ConfigReconciler reconciler = new ConfigReconciler(null, null) {
            @Override public boolean reconcile(Device d, Set<String> t, String rev, String agentVersion,
                                               String configAgentVersion, long now, long receivedAt) { return false; }
        };
        RolloutRequeuer requeuer = new RolloutRequeuer(null) {
            @Override public boolean requeue(Device d, long now) { return false; }
        };
        String files = Files.createTempDirectory("files").toString();
        InstallHashes hashes = new InstallHashes(new ApkHashResolver(files, "https://mdm.example.com"), unsecureDAO);
        resource = new AgentResource(unsecureDAO, null, commands,
                new ConfigAppInstaller(null, null, null, null), reconciler, requeuer, hashes,
                new KioskLockoutAlert(null, null, null));
    }

    private List<com.hmdm.rest.json.agent.AgentCommand> checkIn() {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(DEVICE);
        Response r = resource.checkin("Bearer " + SECRET, null, req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        return ((AgentCheckInResponse) r.getData()).getCommands();
    }

    @Test
    public void aStoredHashIsFilledInAtDelivery() {
        storedHash = HEX;
        List<com.hmdm.rest.json.agent.AgentCommand> sent = checkIn();
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).getPayload().toString(), sent.get(0).getPayload().toString().contains(HEX));
        assertEquals(Collections.emptyList(), marked);
    }

    @Test
    public void noHashAtAllEndsTheCommand() {
        storedHash = null;
        assertTrue(checkIn().isEmpty());
        assertEquals(Collections.singletonList("7:failed"), marked);
        assertEquals(Collections.emptyList(), claimed);
    }

    @Test
    public void aFailedLookupLeavesTheCommandPendingForTheNextCheckIn() {
        lookupFails = true;
        assertTrue(checkIn().isEmpty());
        assertEquals("not failed, not claimed", Collections.emptyList(), marked);
        assertEquals(Collections.emptyList(), claimed);
    }
}
