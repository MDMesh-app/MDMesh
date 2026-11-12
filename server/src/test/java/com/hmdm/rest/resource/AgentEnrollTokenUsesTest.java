/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A token for several devices, through the enroll endpoint and in the SQL that counts its uses. What a refused or
 * failed enrollment keeps (nothing: its transaction rolls back) is AgentEnrollmentTransactionTest's, against PostgreSQL.
 */
public class AgentEnrollTokenUsesTest {

    @Test
    public void a_token_for_two_devices_enrolls_two_and_refuses_a_third() {
        EnrollFixture f = new EnrollFixture();
        f.token.setMaxUses(2);

        assertEquals(Response.ResponseStatus.OK, f.enroll(null).getStatus());
        assertFalse("one use left", f.token.isUsed());
        assertEquals(Response.ResponseStatus.OK, f.enroll(null).getStatus());
        assertTrue("no use left", f.token.isUsed());

        Response third = f.enroll(null);
        assertEquals(Response.ResponseStatus.ERROR, third.getStatus());
        assertEquals("error.agent.token.used", third.getMessage());
        assertEquals(2, f.token.getUseCount());
        assertEquals(2, f.created.size());
    }

    @Test
    public void an_expired_code_says_so() {
        EnrollFixture f = new EnrollFixture();
        f.token.setExpiresAt(System.currentTimeMillis() - 1);

        assertEquals("error.agent.token.expired", f.enroll(null).getMessage());
        assertTrue(f.created.isEmpty());
    }

    @Test
    public void claiming_takes_one_use_only_while_one_is_left_and_marks_the_last() {
        // The guard that keeps concurrent enrolls from creating more devices than the token allows is this one
        // statement (and the token row lock the enrollment transaction takes first); scripts/agent-v1-e2e.sh and
        // AgentEnrollmentTransactionTest run it against PostgreSQL, in parallel.
        String sql = Fakes.sqlOf(AgentEnrollmentTokenMapper.class, "claim").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.contains("set usecount = usecount + 1, used = (usecount + 1 >= maxuses)"));
        String where = Fakes.whereOf(sql);
        assertTrue(where, where.contains("used = false"));
        assertTrue(where, where.contains("usecount < maxuses"));
        assertTrue(where, where.contains("expiresat is null or expiresat > #{now}"));
    }

    @Test
    public void the_enrollment_locks_its_token_row_with_a_bounded_wait() {
        String lock = Fakes.sqlOf(AgentEnrollmentTokenMapper.class, "lockForEnroll").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(lock, lock.contains("from agentenrollmenttoken where id = #{id} for update"));
        String timeout = Fakes.sqlOf(AgentEnrollmentTokenMapper.class, "setEnrollLockTimeout").toLowerCase();
        assertTrue("transaction-local, as set local: " + timeout,
                timeout.contains("set_config('lock_timeout', #{timeout}, true)"));
    }
}
