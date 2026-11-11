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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * An agent whose enroll response was lost (Wi-Fi dropped during setup, the app stopped after the request) retries
 * with the same enroll attempt id and gets the device the first request created, instead of "code already used" and
 * an orphan device, or a second device from a batch code. Concurrent retries: AgentEnrollmentTransactionTest.
 */
public class AgentEnrollRetryTest {

    private static final String ATTEMPT = "Zq3v8yK1mN0pR5tW7xA2bC";

    private final EnrollFixture f = new EnrollFixture();

    private static AgentEnrollResponse data(Response r) {
        assertEquals(r.getMessage(), Response.ResponseStatus.OK, r.getStatus());
        return (AgentEnrollResponse) r.getData();
    }

    @Test
    public void a_retry_of_the_same_attempt_gets_the_same_device_and_the_same_secret() {
        AgentEnrollResponse first = data(f.enroll(ATTEMPT));

        AgentEnrollResponse retry = data(f.enroll(ATTEMPT));

        assertEquals(first.getDeviceId(), retry.getDeviceId());
        assertEquals("whichever answer reached the agent works", first.getDeviceSecret(), retry.getDeviceSecret());
        assertEquals(CryptoUtil.getSHA256String(retry.getDeviceSecret()), f.secretHashes.get(first.getDeviceId()));
        assertEquals("one device", 1, f.created.size());
        assertEquals("one use", 1, f.token.getUseCount());
    }

    @Test
    public void a_retry_does_not_take_another_use_of_a_batch_code() {
        f.token.setMaxUses(5);
        data(f.enroll(ATTEMPT));
        data(f.enroll(ATTEMPT));
        data(f.enroll(ATTEMPT));

        assertEquals(1, f.token.getUseCount());
        assertEquals(1, f.created.size());
    }

    @Test
    public void another_attempt_with_a_used_single_use_code_is_refused() {
        data(f.enroll(ATTEMPT));

        assertEquals("error.agent.token.used", f.enroll("Other0attempt0value0xyz").getMessage());
    }

    @Test
    public void without_an_attempt_a_used_code_is_refused_as_before() {
        data(f.enroll(null));

        assertEquals("error.agent.token.used", f.enroll(null).getMessage());
    }

    @Test
    public void the_retry_window_closes_at_the_first_check_in() {
        AgentEnrollResponse first = data(f.enroll(ATTEMPT));
        f.checkIn(first.getDeviceId());

        assertEquals("error.agent.token.used", f.enroll(ATTEMPT).getMessage());
        assertEquals(CryptoUtil.getSHA256String(first.getDeviceSecret()), f.secretHashes.get(first.getDeviceId()));
    }

    @Test
    public void a_stopped_code_is_not_retried() {
        AgentEnrollResponse first = data(f.enroll(ATTEMPT));
        f.token.setRevokedAt(System.currentTimeMillis());

        assertEquals("error.agent.token.used", f.enroll(ATTEMPT).getMessage());
        assertEquals(CryptoUtil.getSHA256String(first.getDeviceSecret()), f.secretHashes.get(first.getDeviceId()));
    }

    @Test
    public void a_code_that_expired_since_still_resumes_its_own_enrollment() {
        AgentEnrollResponse first = data(f.enroll(ATTEMPT));
        f.token.setExpiresAt(System.currentTimeMillis() - 1);

        assertEquals(first.getDeviceId(), data(f.enroll(ATTEMPT)).getDeviceId());
    }

    @Test
    public void the_attempt_is_bound_to_the_code_and_stored_hashed() {
        String hash = AgentResource.enrollAttemptHash(1, ATTEMPT);
        assertEquals(64, hash.length());
        assertTrue(!hash.contains(ATTEMPT));
        assertNotEquals(hash, AgentResource.enrollAttemptHash(2, ATTEMPT));
    }

    @Test
    public void a_malformed_attempt_is_ignored() {
        assertNull("too short to be unguessable", AgentResource.enrollAttemptHash(1, "short"));
        assertNull(AgentResource.enrollAttemptHash(1, "has spaces in it, not allowed!!"));
        assertNull(AgentResource.enrollAttemptHash(1, null));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 200; i++) huge.append('a');
        assertNull(AgentResource.enrollAttemptHash(1, huge.toString()));

        data(f.enroll("short"));
        assertEquals("error.agent.token.used", f.enroll("short").getMessage());
    }

    @Test
    public void the_retry_only_resumes_a_completed_device_its_attempt_still_owns() {
        String find = Fakes.whereOf(Fakes.sqlOf(AgentDeviceMapper.class, "findEnrollRetry"));
        assertTrue(find, find.contains("agentenrollattempthash = #{attempthash}"));
        assertTrue(find, find.contains("enrolltime >= #{since}"));
        assertTrue(find, find.contains("agentsecrethash is not null"));
        String reissue = Fakes.sqlOf(AgentDeviceMapper.class, "reissueSecret").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(reissue, reissue.contains("set agentsecrethash = #{secrethash}"));
        assertTrue("a resumed enrollment doesn't look checked in", !reissue.contains("lastupdate"));
        String where = Fakes.whereOf(reissue);
        assertTrue(where, where.contains("agentenrollattempthash = #{attempthash}"));
        assertTrue(where, where.contains("agentsecrethash is not null"));
        String checkIn = Fakes.sqlOf(AgentDeviceMapper.class, "markCheckedIn").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(checkIn, checkIn.contains("agentenrollattempthash = null"));
    }
}
