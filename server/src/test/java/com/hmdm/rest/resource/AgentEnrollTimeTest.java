package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.rest.json.agent.AgentEnrollResponse;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The agent's /enroll stamps the new device's enrollment time, the console's "Enrolled". Only the legacy launcher
 * sync (off by default) set it before, so every agent-enrolled device showed none.
 */
public class AgentEnrollTimeTest {

    private static final String TOKEN = "enroll-token";

    private static final class FakeTokenDAO extends AgentEnrollmentTokenDAO {
        final AgentEnrollmentToken token = new AgentEnrollmentToken();

        FakeTokenDAO() {
            super(null);
            token.setId(1);
            token.setToken(TOKEN);
            token.setCustomerId(1);
        }

        @Override public AgentEnrollmentToken findByToken(String t) { return TOKEN.equals(t) ? token : null; }
        @Override public boolean claim(Integer id) {
            if (token.isUsed()) return false;
            token.setUsed(true);
            return true;
        }
        @Override public void release(Integer id) { token.setUsed(false); }
    }

    private static final class FakeUnsecureDAO extends UnsecureDAO {
        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, "/tmp", 0, "x");
        }

        @Override public Device createNewDeviceForToken(String deviceId, int customerId, Integer configurationId) {
            Device d = new Device();
            d.setNumber(deviceId);
            d.setCustomerId(customerId);
            return d;
        }
        @Override public Configuration getConfigurationById(Integer id) { return null; }
    }

    private static final class FakeCommandDAO extends AgentCommandDAO {
        final List<String> enrollTimeRecorded = new ArrayList<>();

        FakeCommandDAO() { super(null, null, null, null); }

        @Override public void updateDeviceSecretHash(String n, String hash) { }
        @Override public void updateDeviceCapabilities(String n, String json) { }
        @Override public void updateHardwareId(String n, String h) { }
        @Override public void touchLastUpdate(String n) { }
        @Override public void recordEnrollTime(String n) { enrollTimeRecorded.add(n); }
    }

    private static ConfigAppInstaller noConfigApps() {
        return new ConfigAppInstaller(null, null, null, null) {
            @Override public int enqueueConfigApps(Device device) { return 0; }
        };
    }

    @Test
    public void enroll_records_the_new_devices_enrollment_time() {
        FakeCommandDAO commands = new FakeCommandDAO();
        AgentResource resource = new AgentResource(new FakeUnsecureDAO(), new FakeTokenDAO(), commands,
                noConfigApps(), null, null, null, null);
        AgentEnrollRequest req = new AgentEnrollRequest();
        req.setEnrollToken(TOKEN);

        Response r = resource.enroll(req);

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        String deviceId = ((AgentEnrollResponse) r.getData()).getDeviceId();
        assertEquals(Collections.singletonList(deviceId), commands.enrollTimeRecorded);
    }

    @Test
    public void a_refused_enroll_records_no_enrollment_time() {
        FakeCommandDAO commands = new FakeCommandDAO();
        AgentResource resource = new AgentResource(new FakeUnsecureDAO(), new FakeTokenDAO(), commands,
                noConfigApps(), null, null, null, null);
        AgentEnrollRequest req = new AgentEnrollRequest();
        req.setEnrollToken("not-a-token");

        Response r = resource.enroll(req);

        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertTrue(commands.enrollTimeRecorded.isEmpty());
    }
}
