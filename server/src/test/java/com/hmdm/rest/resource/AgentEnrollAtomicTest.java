/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentEnrollResponse;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.CryptoUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the endpoint answers for each way an enrollment ends. That a failed one keeps nothing (its transaction rolls
 * back) is AgentEnrollmentTransactionIT's, against PostgreSQL.
 */
public class AgentEnrollAtomicTest {

    private static final String[] FINAL_MARKERS = {"token.used", "token.invalid", "token.expired", "enrollment.disabled"};

    @Test
    public void a_completed_enrollment_stores_the_hash_of_the_secret_it_returns() {
        EnrollFixture f = new EnrollFixture();

        AgentEnrollResponse data = (AgentEnrollResponse) f.enroll(null).getData();

        assertEquals(1, f.completed.size());
        assertEquals(data.getDeviceId(), f.completed.get(0));
        assertEquals(CryptoUtil.getSHA256String(data.getDeviceSecret()), f.secretHashes.get(data.getDeviceId()));
    }

    @Test
    public void config_apps_are_queued_after_the_enrollment_and_cannot_undo_it() {
        EnrollFixture f = new EnrollFixture();
        AgentEnrollResponse data = (AgentEnrollResponse) f.enroll(null).getData();
        assertEquals(java.util.Collections.singletonList(data.getDeviceId()), f.configAppsQueuedFor);

        EnrollFixture failing = new EnrollFixture();
        failing.configAppsThrow = true;
        assertEquals(Response.ResponseStatus.OK, failing.enroll(null).getStatus());
    }

    @Test
    public void a_database_failure_is_answered_with_an_error_the_agent_retries() {
        for (String completion : new String[]{"throw", "none"}) {
            EnrollFixture f = new EnrollFixture();
            f.completion = completion;

            String message = f.enroll(null).getMessage();

            assertEquals(completion, "error.agent.enrollment.failed", message);
            for (String terminal : FINAL_MARKERS) assertFalse(message, message.contains(terminal));
        }
    }

    @Test
    public void a_lock_held_too_long_by_another_enrollment_with_the_code_is_retried() {
        EnrollFixture f = new EnrollFixture();
        f.lockTimesOut = true;

        String message = f.enroll("Zq3v8yK1mN0pR5tW7xA2bC").getMessage();

        assertEquals("error.agent.enrollment.inProgress", message);
        for (String terminal : FINAL_MARKERS) assertFalse(message, message.contains(terminal));
        assertTrue(f.created.isEmpty());
    }

    @Test
    public void a_customer_at_its_device_limit_gets_a_named_error_the_agent_retries() {
        EnrollFixture f = new EnrollFixture();
        f.atDeviceLimit = true;

        String message = f.enroll("Zq3v8yK1mN0pR5tW7xA2bC").getMessage();

        assertEquals("error.agent.enrollment.deviceLimit", message);
        for (String terminal : FINAL_MARKERS) assertFalse(message, message.contains(terminal));
        assertTrue(f.created.isEmpty());
    }

    @Test
    public void the_device_limit_is_counted_under_the_customers_row_lock() {
        String lock = Fakes.sqlOf(AgentDeviceMapper.class, "lockCustomerDeviceLimit").replaceAll("\\s+", " ")
                .toLowerCase();
        assertTrue(lock, lock.contains("from customers where id = #{customerid} for update"));
    }

    @Test
    public void completing_sets_everything_a_device_needs_in_one_statement() {
        String sql = Fakes.sqlOf(AgentDeviceMapper.class, "completeEnrollment").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.startsWith("update devices set"));
        assertTrue(sql, sql.contains("agentsecrethash = #{secrethash}"));
        assertTrue(sql, sql.contains("agentcapabilities = #{capabilitiesjson}"));
        assertTrue(sql, sql.contains("hardwareid = coalesce(#{hardwareid}, hardwareid)"));
        assertTrue(sql, sql.contains("lastupdate = #{ts}"));
        assertTrue(sql, sql.contains("agentenrollattempthash = #{attempthash}"));
    }
}
