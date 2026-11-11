/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.rest.json.agent.AgentEnrollResponse;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import static com.hmdm.rest.resource.AgentEnrollTimeTest.TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An enrollment either completes (the device has its secret) or leaves nothing behind: no device that can never
 * authenticate, and the code's use given back so the agent's retry works.
 */
public class AgentEnrollAtomicTest {

    private final AgentEnrollTimeTest.FakeTokenDAO tokens = new AgentEnrollTimeTest.FakeTokenDAO();
    private final AgentEnrollTimeTest.FakeCommandDAO commands = new AgentEnrollTimeTest.FakeCommandDAO();
    private final AgentEnrollTimeTest.FakeUnsecureDAO unsecure = new AgentEnrollTimeTest.FakeUnsecureDAO();
    private final AgentResource resource = new AgentResource(unsecure, tokens,
            commands, AgentEnrollTimeTest.noConfigApps(), null, null, null, null, null);

    private Response enroll() {
        AgentEnrollRequest req = new AgentEnrollRequest();
        req.setEnrollToken(TOKEN);
        req.setHardwareId("  hw-1 ");
        return resource.enroll(req);
    }

    @Test
    public void a_completed_enrollment_stores_the_secret_hash_of_the_secret_it_returns() {
        Response r = enroll();

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        AgentEnrollResponse data = (AgentEnrollResponse) r.getData();
        assertEquals(1, commands.completed.size());
        assertEquals(data.getDeviceId(), commands.completed.get(0));
        assertEquals(com.hmdm.util.CryptoUtil.getSHA256String(data.getDeviceSecret()),
                commands.secretHashes.get(data.getDeviceId()));
        assertTrue(commands.deleted.isEmpty());
        assertTrue("the use is kept", tokens.token.isUsed());
    }

    @Test
    public void a_failure_while_completing_deletes_the_new_device_and_gives_the_use_back() {
        commands.completion = "throw";

        Response r = enroll();

        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals("error.agent.enrollment.failed", r.getMessage());
        assertEquals("the device without a secret is removed", 1, commands.deleted.size());
        assertFalse("the use is given back for the retry", tokens.token.isUsed());
        assertEquals(0, tokens.token.getUseCount());
    }

    @Test
    public void a_device_that_vanished_before_completing_is_not_left_half_enrolled() {
        commands.completion = "none";

        Response r = enroll();

        assertEquals("error.agent.enrollment.failed", r.getMessage());
        assertEquals(1, commands.deleted.size());
        assertFalse(tokens.token.isUsed());
    }

    @Test
    public void a_failure_reading_the_new_device_back_still_removes_it() {
        unsecure.failAfterInsert = true;

        Response r = enroll();

        assertEquals("error.agent.enrollment.failed", r.getMessage());
        assertEquals("the row inserted before the failure is removed", unsecure.inserted, commands.deleted);
        assertFalse(tokens.token.isUsed());
    }

    @Test
    public void the_retryable_error_is_not_one_agents_treat_as_final() {
        // Agents since 0.2 stop retrying on these markers (CheckInWorker.TERMINAL_ENROLL_MARKERS).
        commands.completion = "throw";
        String message = enroll().getMessage();
        for (String terminal : new String[]{"token.used", "token.invalid", "token.expired", "enrollment.disabled"}) {
            assertFalse(message, message.contains(terminal));
        }
    }

    @Test
    public void completing_is_one_statement_that_sets_everything_a_device_needs() {
        String sql = Fakes.sqlOf(AgentDeviceMapper.class, "completeEnrollment").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.startsWith("update devices set"));
        assertTrue(sql, sql.contains("agentsecrethash = #{secrethash}"));
        assertTrue(sql, sql.contains("agentcapabilities = #{capabilitiesjson}"));
        assertTrue(sql, sql.contains("hardwareid = coalesce(#{hardwareid}, hardwareid)"));
        assertTrue(sql, sql.contains("lastupdate = #{ts}"));
    }

    @Test
    public void only_a_device_without_a_secret_is_deleted() {
        String sql = Fakes.sqlOf(AgentDeviceMapper.class, "deleteUnenrolledDevice").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.startsWith("delete from devices"));
        String where = Fakes.whereOf(sql);
        assertTrue(where, where.contains("number = #{devicenumber}"));
        assertTrue(where, where.contains("agentsecrethash is null"));
    }
}
