package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCapabilities;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCheckInResponse;
import com.hmdm.rest.json.agent.AgentCommandResult;
import com.hmdm.rest.json.agent.AgentDeviceState;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.RolloutRequeuer;
import com.hmdm.util.ConfigReconcileDecision;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.DesiredConfigBuilder;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The real check-in path ({@link AgentResource#checkin} -> {@link ConfigReconciler}) over fake DAOs that keep the
 * SQL semantics that matter here: the device_state upsert COALESCEs a missing applied revision and never touches
 * configAgentVersion. Covers the agent-update resync: a new agent version gets config.apply exactly once, so the
 * per-key results (e.g. an old agent's {@code policies.safeBoot = unsupported}) refresh.
 */
public class AgentCheckInConfigResyncTest {

    private static final String NUMBER = "d1";
    private static final String SECRET = "sek";
    private static final String CAPS = "{\"device\":[\"configApply\"],\"policy\":[\"safeBoot\"]}";

    private static final class FakeUnsecureDAO extends UnsecureDAO {
        Device device;
        Configuration configuration;

        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, "/tmp", 0, "x");
        }

        @Override public Device getDeviceByNumber(String number) { return NUMBER.equals(number) ? device : null; }
        @Override public Configuration getConfigurationById(Integer id) { return configuration; }
        @Override public List<Application> getPlainConfigurationAppsOptimized(Integer configurationId) {
            return Collections.emptyList();
        }
    }

    /** In-memory agentCommand + device_state with the production upsert/claim semantics. */
    private static final class FakeCommandDAO extends AgentCommandDAO {
        final List<AgentCommand> commands = new ArrayList<>();
        DeviceState state;
        String caps;

        FakeCommandDAO() { super(null, null, null, null); }

        @Override public String getDeviceSecretHash(String n) { return CryptoUtil.getSHA256String(SECRET); }
        @Override public void touchLastUpdate(String n) { }
        @Override public void updateHardwareId(String n, String h) { }
        @Override public void updateDeviceCapabilities(String n, String json) { caps = json; }
        @Override public String getDeviceCapabilities(String n) { return caps; }
        @Override public void updateAndroidVersion(String n, String v) { }
        @Override public void insertEvent(String n, String type, Long ts, String detail) { }
        @Override public void expireStale(String n, long pendingTtl, long deliveredTtl) { }

        @Override public void upsertState(DeviceState row) {
            if (state != null) {
                // COALESCE(EXCLUDED.appliedConfigRevision, device_state.appliedConfigRevision); configAgentVersion kept.
                if (row.getAppliedConfigRevision() == null) row.setAppliedConfigRevision(state.getAppliedConfigRevision());
                row.setConfigAgentVersion(state.getConfigAgentVersion());
            }
            state = row;
        }
        @Override public DeviceState getState(String n) { return state; }
        @Override public void updateConfigAgentVersion(String n, String version) {
            if (state != null) state.setConfigAgentVersion(version);
        }
        @Override public void recordConfigApplyDone(String n, Integer id) {
            // SET configAgentVersion = COALESCE(<the command's agentVersion>, agentVersion)
            // WHERE that IS NOT NULL AND <id is a config.apply>
            AgentCommand c = commands.get(id - 1);
            String version = c.getAgentVersion() != null ? c.getAgentVersion()
                    : state == null ? null : state.getAgentVersion();
            if (state != null && version != null && DesiredConfigBuilder.COMMAND_TYPE.equals(c.getType())) {
                state.setConfigAgentVersion(version);
            }
        }

        final List<com.hmdm.persistence.domain.DeviceLocation> locations = new ArrayList<>();
        @Override public void recordLocation(com.hmdm.persistence.domain.DeviceLocation l) { locations.add(l); }

        @Override public void insert(AgentCommand c) { c.setId(commands.size() + 1); commands.add(c); }
        @Override public List<AgentCommand> listPending(String n) {
            List<AgentCommand> out = new ArrayList<>();
            for (AgentCommand c : commands) if ("pending".equals(c.getStatus())) out.add(c);
            return out;
        }
        @Override public boolean claimForDelivery(Integer id, Long at) {
            AgentCommand c = commands.get(id - 1);
            if (!"pending".equals(c.getStatus())) return false;
            c.setStatus("delivered");
            return true;
        }
        @Override public void markResultWithTime(String n, Integer id, String status, String detail, Long at) {
            AgentCommand c = commands.get(id - 1);
            c.setStatus(status);
            c.setCompletedAt(at);
        }
        @Override public boolean hasOpenOfType(String n, String type) {
            for (AgentCommand c : commands) {
                if (type.equals(c.getType()) && ("pending".equals(c.getStatus()) || "delivered".equals(c.getStatus()))) {
                    return true;
                }
            }
            return false;
        }
        @Override public AgentCommand findLatestOfType(String n, String type) {
            AgentCommand latest = null;
            for (AgentCommand c : commands) if (type.equals(c.getType())) latest = c;
            return latest;
        }
    }

    private FakeCommandDAO commandDAO;
    private FakeUnsecureDAO unsecureDAO;
    private AgentResource resource;
    private String revision;
    /** Results the "agent" sends on its next check-in. */
    private final List<AgentCommandResult> outbox = new ArrayList<>();

    @Before
    public void setUp() {
        unsecureDAO = new FakeUnsecureDAO();
        Device device = new Device();
        device.setNumber(NUMBER);
        device.setCustomerId(1);
        device.setConfigurationId(3);
        unsecureDAO.device = device;
        Configuration cfg = new Configuration();
        cfg.setId(3);
        cfg.setDisableSafeBoot(true);
        unsecureDAO.configuration = cfg;
        revision = DesiredConfigBuilder.build(cfg, Collections.<Application>emptyList()).getRevision();

        commandDAO = new FakeCommandDAO();
        RolloutRequeuer noRollout = new RolloutRequeuer(null) {
            @Override public boolean requeue(Device d, long now) { return false; }
        };
        resource = new AgentResource(unsecureDAO, null, commandDAO, null,
                new ConfigReconciler(unsecureDAO, commandDAO), noRollout,
                new InstallHashes(null, unsecureDAO));
    }

    /** One agent check-in; the agent acks every command it receives as done on the following check-in. */
    private List<com.hmdm.rest.json.agent.AgentCommand> checkIn(String agentVersion, String appliedRevision) throws Exception {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(NUMBER);
        req.setCapabilities(new AgentCapabilities(new ObjectMapper().readTree(CAPS)));
        AgentDeviceState s = new AgentDeviceState();
        s.setAgentVersion(agentVersion);
        s.setAppliedConfigRevision(appliedRevision);
        req.setState(s);
        req.setResults(new ArrayList<>(outbox));
        outbox.clear();

        Response r = resource.checkin("Bearer " + SECRET, null, req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        List<com.hmdm.rest.json.agent.AgentCommand> got = ((AgentCheckInResponse) r.getData()).getCommands();
        for (com.hmdm.rest.json.agent.AgentCommand c : got) {
            AgentCommandResult done = new AgentCommandResult();
            done.setCommandId(c.getCommandId());
            done.setStatus("done");
            outbox.add(done);
        }
        return got;
    }

    private int configApplies() {
        int n = 0;
        for (AgentCommand c : commandDAO.commands) if (DesiredConfigBuilder.COMMAND_TYPE.equals(c.getType())) n++;
        return n;
    }

    @Test
    public void agent_version_change_resends_config_apply_exactly_once() throws Exception {
        // Converge on the old agent (it reports safeBoot unsupported, which still counts as applied).
        assertEquals(1, checkIn("0.3.2", null).size());
        assertEquals(0, checkIn("0.3.2", revision).size());
        assertEquals(0, checkIn("0.3.2", revision).size());

        // Self-update: same revision on the device, new agent version -> one fresh config.apply.
        List<com.hmdm.rest.json.agent.AgentCommand> resent = checkIn("0.3.3", revision);
        assertEquals(1, resent.size());
        assertEquals(DesiredConfigBuilder.COMMAND_TYPE, resent.get(0).getType());

        // Acked, and from then on nothing more: once per version change, not per check-in.
        assertEquals(0, checkIn("0.3.3", revision).size());
        assertEquals(0, checkIn("0.3.3", revision).size());
        assertEquals(2, configApplies());
        assertEquals("done", commandDAO.commands.get(1).getStatus());
    }

    @Test
    public void a_config_apply_acked_after_a_self_update_still_resyncs_the_new_agent() throws Exception {
        // Queued for and delivered to 0.3.2; the agent updates itself before its result reaches the server, so the
        // done arrives from 0.3.3. It records 0.3.2 (what applied it), and 0.3.3 gets its own config.apply.
        assertEquals(1, checkIn("0.3.2", null).size());
        assertEquals(1, checkIn("0.3.3", revision).size());
        assertEquals(0, checkIn("0.3.3", revision).size());
        assertEquals("0.3.3", commandDAO.getState(NUMBER).getConfigAgentVersion());
        assertEquals(0, checkIn("0.3.3", revision).size());
        assertEquals(2, configApplies());
    }

    /** Converge on 0.3.2, update to 0.3.3, receive the re-send; returns that re-sent command. */
    private AgentCommand updateAndReceiveResend() throws Exception {
        checkIn("0.3.2", null);
        checkIn("0.3.2", revision);
        assertEquals(1, checkIn("0.3.3", revision).size());
        return commandDAO.commands.get(commandDAO.commands.size() - 1);
    }

    private void moveBackBeyondBackoff(AgentCommand c) {
        c.setCompletedAt(System.currentTimeMillis() - 2 * ConfigReconcileDecision.RETRY_BACKOFF_MS);
    }

    @Test
    public void a_lost_resend_that_expires_does_not_consume_the_resync() throws Exception {
        AgentCommand resent = updateAndReceiveResend();
        outbox.clear(); // the response never reached the device; the server later expires the command
        resent.setStatus("expired");
        resent.setCompletedAt(System.currentTimeMillis());

        assertEquals("expiry keeps the normal backoff", 0, checkIn("0.3.3", revision).size());
        assertEquals("0.3.2", commandDAO.getState(NUMBER).getConfigAgentVersion());

        moveBackBeyondBackoff(resent);
        assertEquals("retried after the backoff", 1, checkIn("0.3.3", revision).size());
        checkIn("0.3.3", revision); // acked done
        assertEquals("0.3.3", commandDAO.getState(NUMBER).getConfigAgentVersion());
        assertEquals(0, checkIn("0.3.3", revision).size());
    }

    @Test
    public void a_failed_resend_is_retried_with_the_normal_backoff_not_every_check_in() throws Exception {
        AgentCommand resent = updateAndReceiveResend();
        outbox.get(0).setStatus("failed");

        assertEquals(0, checkIn("0.3.3", revision).size()); // carries the failure
        assertEquals(0, checkIn("0.3.3", revision).size());
        assertEquals("failed", resent.getStatus());
        assertEquals("a failure does not record the new version", "0.3.2",
                commandDAO.getState(NUMBER).getConfigAgentVersion());

        moveBackBeyondBackoff(resent);
        assertEquals(1, checkIn("0.3.3", revision).size());
    }

    @Test
    public void same_agent_version_in_sync_sends_nothing() throws Exception {
        checkIn("0.3.3", null);
        checkIn("0.3.3", revision);
        for (int i = 0; i < 3; i++) assertEquals(0, checkIn("0.3.3", revision).size());
        assertEquals(1, configApplies());
    }

    @Test
    public void first_sighting_of_an_in_sync_device_only_records_a_baseline() throws Exception {
        // E.g. right after the server upgrade added the column: no fleet-wide re-send.
        commandDAO.state = new DeviceState();
        commandDAO.state.setDeviceNumber(NUMBER);
        commandDAO.state.setAppliedConfigRevision(revision);
        assertNull(commandDAO.state.getConfigAgentVersion());

        assertEquals(0, checkIn("0.3.2", revision).size());
        assertEquals("0.3.2", commandDAO.getState(NUMBER).getConfigAgentVersion());
        assertEquals(1, checkIn("0.3.3", revision).size());
    }

    @Test
    public void a_check_in_without_state_never_triggers_a_resync() throws Exception {
        checkIn("0.3.2", null);
        checkIn("0.3.2", revision);
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(NUMBER);
        req.setCapabilities(new AgentCapabilities(new ObjectMapper().readTree(CAPS)));
        Response r = resource.checkin("Bearer " + SECRET, null, req);
        assertEquals(0, ((AgentCheckInResponse) r.getData()).getCommands().size());
    }

    /** A check-in from an agent that (whatever its mode) still reports a location fix in its telemetry. */
    private void checkInWithLocation(String caps) throws Exception {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(NUMBER);
        req.setCapabilities(new AgentCapabilities(new ObjectMapper().readTree(caps)));
        AgentDeviceState s = new AgentDeviceState();
        s.setAgentVersion("0.3.3");
        req.setState(s);
        req.setTelemetry(new ObjectMapper().readTree(
                "{\"dynamic\":{\"batteryPct\":50,\"location\":{\"lat\":52.1,\"lon\":4.3,\"capturedAt\":1}}}"));
        assertEquals(Response.ResponseStatus.OK, resource.checkin("Bearer " + SECRET, null, req).getStatus());
    }

    @Test
    public void do_not_track_configuration_never_stores_a_location_even_from_an_old_agent() throws Exception {
        // 0.3.1-0.3.3 agents read "off" as passive and keep sending last-known fixes: the server drops them.
        unsecureDAO.configuration.setRequestUpdates(com.hmdm.persistence.domain.RequestUpdatesType.DONOTTRACK);
        checkInWithLocation(CAPS);
        assertEquals(0, commandDAO.locations.size());
        String telemetry = commandDAO.getState(NUMBER).getTelemetry();
        assertEquals("the rest of the telemetry is kept", true, telemetry.contains("\"batteryPct\":50"));
        assertEquals("no location in the stored telemetry", false, telemetry.contains("\"location\""));
    }

    @Test
    public void tracked_configurations_still_store_the_location() throws Exception {
        unsecureDAO.configuration.setRequestUpdates(com.hmdm.persistence.domain.RequestUpdatesType.WIFI);
        checkInWithLocation(CAPS);
        assertEquals(1, commandDAO.locations.size());
        assertEquals(true, commandDAO.getState(NUMBER).getTelemetry().contains("\"location\""));
    }

    @Test
    public void a_device_without_a_configuration_keeps_its_location() throws Exception {
        unsecureDAO.configuration = null;
        checkInWithLocation(CAPS);
        assertEquals(1, commandDAO.locations.size());
    }
}
