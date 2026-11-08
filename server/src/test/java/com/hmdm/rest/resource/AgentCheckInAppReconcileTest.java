package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.RolloutDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationVersion;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCapabilities;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCheckInResponse;
import com.hmdm.rest.json.agent.AgentCommandResult;
import com.hmdm.rest.json.agent.AgentDeviceState;
import com.hmdm.rest.resource.support.ApkHashResolver;
import com.hmdm.rest.resource.support.AppReconciler;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskLockoutAlert;
import com.hmdm.rest.resource.support.RolloutRequeuer;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.AppReconcileDecision;
import com.hmdm.util.CryptoUtil;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The real check-in path ({@link AgentResource#checkin} -> {@link AppReconciler}) over fake DAOs: an app added to the
 * device's configuration rides the next check-in's response, once; only an authenticated agent that can install
 * silently is ever sent one.
 */
public class AgentCheckInAppReconcileTest {

    private static final String NUMBER = "d1";
    private static final String SECRET = "sek";
    private static final String HEX = "ab".repeat(32);
    private static final String CAN_INSTALL = "{\"appManagement\":[\"silentInstall\"]}";

    private final List<Application> apps = new ArrayList<>();
    private final List<AgentCommand> commands = new ArrayList<>();
    private DeviceState state;
    private AgentResource resource;
    private final List<AgentCommandResult> outbox = new ArrayList<>();

    @Before
    public void setUp() {
        UnsecureDAO unsecureDAO = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, "unused", 0, null) {
            @Override public Device getDeviceByNumber(String number) {
                if (!NUMBER.equals(number)) return null;
                Device d = new Device();
                d.setNumber(NUMBER);
                d.setCustomerId(1);
                d.setConfigurationId(3);
                return d;
            }
            @Override public List<Integer> getConfigurationInstallVersionIds(Integer id) {
                List<Integer> ids = new ArrayList<>();
                for (Application a : apps) ids.add(a.getUsedVersionId());
                return ids;
            }
            @Override public List<Application> getPlainConfigurationApplications(Integer customerId, Integer id) {
                return apps;
            }
            @Override public ApplicationVersion findApplicationVersionById(Integer id) { return null; }
        };
        AgentCommandDAO commandDAO = new AgentCommandDAO(null, null, null, null) {
            @Override public String getDeviceSecretHash(String n) { return CryptoUtil.getSHA256String(SECRET); }
            @Override public void touchLastUpdate(String n) { }
            @Override public void updateDeviceCapabilities(String n, String json) { }
            @Override public void expireStale(String n, long pendingTtl, long deliveredTtl) { }
            @Override public void recordConfigApplyDone(String n, Integer id) { }
            @Override public void upsertState(DeviceState row) {
                if (state != null) row.setAppsRevision(state.getAppsRevision());   // the upsert never writes it
                state = row;
            }
            @Override public DeviceState getState(String n) { return state; }
            @Override public void updateAppsRevision(String n, String revision) {
                if (state != null) state.setAppsRevision(revision);
            }
            @Override public void insert(AgentCommand c) {
                c.setId(commands.size() + 1);
                commands.add(c);
            }
            @Override public List<AgentCommand> listLatestAppCommands(String n, int limit) {
                List<AgentCommand> out = new ArrayList<>();
                for (int i = commands.size() - 1; i >= 0; i--) out.add(commands.get(i));
                return out;
            }
            @Override public List<AgentCommand> listPending(String n) {
                List<AgentCommand> out = new ArrayList<>();
                for (AgentCommand c : commands) if ("pending".equals(c.getStatus())) out.add(c);
                return out;
            }
            @Override public boolean claimForDelivery(Integer id, Long at) {
                AgentCommand c = commands.get(id - 1);
                c.setStatus("delivered");
                c.setDeliveredAt(at);
                return true;
            }
            @Override public void markResultWithTime(String n, Integer id, String status, String detail, Long at) {
                AgentCommand c = commands.get(id - 1);
                c.setStatus(status);
                c.setCompletedAt(at);
            }
        };
        RolloutDAO rolloutDAO = new RolloutDAO(null) {
            @Override public boolean insertIfNotInFlight(AgentCommand cmd) {
                commandDAO.insert(cmd);
                return true;
            }
        };
        ApkHashResolver hashes = new ApkHashResolver("/nonexistent", "http://mdm.lan") {
            @Override public String sha256Hex(String url) throws IOException { return HEX; }
        };
        ConfigAppInstaller installer = new ConfigAppInstaller(unsecureDAO, commandDAO, null, hashes);
        resource = new AgentResource(unsecureDAO, null, commandDAO, installer,
                new ConfigReconciler(null, null, Fakes.noExtensionPoints()) {
                    @Override public boolean reconcile(Device d, Set<String> t, String rev, String av, String cav,
                                                       long now, long receivedAt) { return false; }
                },
                new AppReconciler(unsecureDAO, commandDAO, rolloutDAO, installer),
                new RolloutRequeuer(null) { @Override public boolean requeue(Device d, long now) { return false; } },
                new InstallHashes(hashes, unsecureDAO), new KioskLockoutAlert(null, null, null));
        addApp(10, "com.a");
        // An enrolled device's row as the update that added appsRevision left it: com.a's set stored.
        state = new DeviceState();
        state.setDeviceNumber(NUMBER);
        state.setAppsRevision(AppReconcileDecision.revision(3, java.util.Collections.singletonList(10)));
    }

    private void addApp(int versionId, String pkg) {
        Application a = new Application();
        a.setPkg(pkg);
        a.setVersionCode(1);
        a.setAction(1);
        a.setUsedVersionId(versionId);
        a.setUrl("http://mdm.lan/files/" + pkg + ".apk");
        apps.add(a);
    }

    /** One agent check-in; the agent installs everything it receives and reports done on the next one. */
    private List<com.hmdm.rest.json.agent.AgentCommand> checkIn(String caps, String secret) throws Exception {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(NUMBER);
        req.setCapabilities(new AgentCapabilities(new ObjectMapper().readTree(caps)));
        AgentDeviceState s = new AgentDeviceState();
        s.setAgentVersion("0.4.0");
        req.setState(s);
        req.setResults(new ArrayList<>(outbox));
        outbox.clear();
        Response r = resource.checkin("Bearer " + secret, null, req);
        if (r.getStatus() != Response.ResponseStatus.OK) return new ArrayList<>();
        List<com.hmdm.rest.json.agent.AgentCommand> got = ((AgentCheckInResponse) r.getData()).getCommands();
        for (com.hmdm.rest.json.agent.AgentCommand c : got) {
            AgentCommandResult done = new AgentCommandResult();
            done.setCommandId(c.getCommandId());
            done.setStatus("done");
            outbox.add(done);
        }
        return got;
    }

    @Test
    public void an_app_added_to_the_configuration_rides_the_next_check_in_once() throws Exception {
        // First check-in after the update: com.a (already on the fleet) is not re-sent.
        assertTrue(checkIn(CAN_INSTALL, SECRET).isEmpty());
        assertTrue(commands.isEmpty());

        addApp(11, "com.b");
        List<com.hmdm.rest.json.agent.AgentCommand> sent = checkIn(CAN_INSTALL, SECRET);
        assertEquals(1, sent.size());
        assertEquals("app.install", sent.get(0).getType());
        assertTrue(sent.get(0).getPayload().toString(), sent.get(0).getPayload().toString().contains("com.b"));

        // Acked done: from then on nothing more.
        assertTrue(checkIn(CAN_INSTALL, SECRET).isEmpty());
        assertTrue(checkIn(CAN_INSTALL, SECRET).isEmpty());
        assertEquals(1, commands.size());
        assertEquals("done", commands.get(0).getStatus());
    }

    @Test
    public void an_agent_that_cannot_install_silently_is_sent_nothing() throws Exception {
        assertTrue(checkIn("{\"device\":[\"configApply\"]}", SECRET).isEmpty());
        addApp(11, "com.b");
        assertTrue(checkIn("{\"device\":[\"configApply\"]}", SECRET).isEmpty());
        assertTrue(commands.isEmpty());
    }

    @Test
    public void a_check_in_that_fails_authentication_queues_nothing() throws Exception {
        assertTrue(checkIn(CAN_INSTALL, SECRET).isEmpty());
        addApp(11, "com.b");
        assertTrue(checkIn(CAN_INSTALL, "wrong").isEmpty());
        assertTrue(commands.isEmpty());
    }
}
