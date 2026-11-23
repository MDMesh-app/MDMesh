package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentEnrollResponse;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The agent's /enroll stamps the new device's enrollment time, the console's "Enrolled", once. */
public class AgentEnrollTimeTest {

    @Test
    public void enroll_completes_the_new_device_which_stamps_its_enrollment_time() {
        EnrollFixture f = new EnrollFixture();

        Response r = f.enroll(null);

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        String deviceId = ((AgentEnrollResponse) r.getData()).getDeviceId();
        assertEquals(Collections.singletonList(deviceId), f.completed);
    }

    @Test
    public void an_enroll_refused_because_creation_is_disabled_completes_nothing() {
        EnrollFixture f = new EnrollFixture();
        f.creationEnabled = false;

        Response r = f.enroll(null);

        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals("error.agent.enrollment.disabled", r.getMessage());
        assertTrue(f.completed.isEmpty());
    }

    @Test
    public void the_enrollment_time_is_set_once() {
        // Enroll stamps it in the UPDATE that completes the enrollment; a time already there (a legacy launcher's
        // first sync) is kept.
        String sql = Fakes.sqlOf(AgentDeviceMapper.class, "completeEnrollment").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.contains("enrolltime = coalesce(enrolltime, #{ts})"));
        assertTrue(sql, Fakes.whereOf(sql).contains("number = #{devicenumber}"));
    }
}
