package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.rest.json.agent.AgentEnrollResponse;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.Call;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The agent's /enroll stamps the new device's enrollment time, the console's "Enrolled", once. */
public class AgentEnrollTimeTest {

    static final String TOKEN = "enroll-token";

    /** Package-visible: AgentEnrollTokenUsesTest enrolls through the same fakes. */
    static final class FakeTokenDAO extends AgentEnrollmentTokenDAO {
        final AgentEnrollmentToken token = new AgentEnrollmentToken();

        FakeTokenDAO() {
            super(null);
            token.setId(1);
            token.setToken(TOKEN);
            token.setCustomerId(1);
        }

        @Override public AgentEnrollmentToken findByToken(String t) { return TOKEN.equals(t) ? token : null; }
        /** As AgentEnrollmentTokenMapper.claim: one use if any is left and it isn't stopped, and used with the last. */
        @Override public boolean claim(Integer id) {
            if (token.isUsed() || token.getUseCount() >= maxUses() || token.getRevokedAt() != null) return false;
            token.setUseCount(token.getUseCount() + 1);
            token.setUsed(token.getUseCount() >= maxUses());
            return true;
        }
        /** As AgentEnrollmentTokenMapper.release: the use back, and not used. */
        @Override public void release(Integer id) {
            token.setUseCount(Math.max(token.getUseCount() - 1, 0));
            token.setUsed(false);
        }
        private int maxUses() { return token.getMaxUses() == null ? 1 : token.getMaxUses(); }
    }

    static final class FakeUnsecureDAO extends UnsecureDAO {
        boolean creationEnabled = true;

        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, "/tmp", 0, "x");
        }

        @Override public Device createNewDeviceForToken(String deviceId, int customerId, Integer configurationId) {
            if (!creationEnabled) return null;
            Device d = new Device();
            d.setNumber(deviceId);
            d.setCustomerId(customerId);
            return d;
        }
        @Override public Configuration getConfigurationById(Integer id) { return null; }
    }

    static final class FakeCommandDAO extends AgentCommandDAO {
        final List<String> enrollTimeRecorded = new ArrayList<>();

        FakeCommandDAO() { super(null, null, null, null); }

        @Override public void updateDeviceSecretHash(String n, String hash) { }
        @Override public void updateDeviceCapabilities(String n, String json) { }
        @Override public void updateHardwareId(String n, String h) { }
        @Override public void touchLastUpdate(String n) { }
        @Override public void recordEnrollTime(String n) { enrollTimeRecorded.add(n); }
    }

    static ConfigAppInstaller noConfigApps() {
        return new ConfigAppInstaller(null, null, null, null) {
            @Override public int enqueueConfigApps(Device device) { return 0; }
        };
    }

    @Test
    public void enroll_records_the_new_devices_enrollment_time() {
        FakeCommandDAO commands = new FakeCommandDAO();
        AgentResource resource = new AgentResource(new FakeUnsecureDAO(), new FakeTokenDAO(), commands,
                noConfigApps(), null, null, null, null, null);
        AgentEnrollRequest req = new AgentEnrollRequest();
        req.setEnrollToken(TOKEN);

        Response r = resource.enroll(req);

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        String deviceId = ((AgentEnrollResponse) r.getData()).getDeviceId();
        assertEquals(Collections.singletonList(deviceId), commands.enrollTimeRecorded);
    }

    @Test
    public void an_enroll_refused_because_creation_is_disabled_records_no_enrollment_time() {
        FakeUnsecureDAO unsecure = new FakeUnsecureDAO();
        unsecure.creationEnabled = false;
        FakeTokenDAO tokens = new FakeTokenDAO();
        FakeCommandDAO commands = new FakeCommandDAO();
        AgentResource resource = new AgentResource(unsecure, tokens, commands, noConfigApps(), null, null, null, null, null);
        AgentEnrollRequest req = new AgentEnrollRequest();
        req.setEnrollToken(TOKEN);

        Response r = resource.enroll(req);

        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals("error.agent.enrollment.disabled", r.getMessage());
        assertTrue(commands.enrollTimeRecorded.isEmpty());
        assertFalse("the token is released for a retry", tokens.token.isUsed());
    }

    @Test
    public void the_enrollment_time_is_set_once() {
        FakeMapper<AgentDeviceMapper> devices = new FakeMapper<>(AgentDeviceMapper.class);
        new AgentCommandDAO(null, devices.mapper, null, null).recordEnrollTime("dev-1");

        List<Call> calls = devices.callsTo("updateEnrollTime");
        assertEquals(1, calls.size());
        assertEquals("dev-1", calls.get(0).args[0]);
        // A time already there (a legacy launcher's first sync) is kept.
        String sql = Fakes.sqlOf(AgentDeviceMapper.class, "updateEnrollTime").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.contains("set enrolltime = coalesce(enrolltime, #{ts})"));
        assertTrue(sql, Fakes.whereOf(sql).contains("number = #{devicenumber}"));
    }
}
