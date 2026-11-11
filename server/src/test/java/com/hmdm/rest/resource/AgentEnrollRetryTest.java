/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.rest.json.agent.AgentEnrollResponse;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.CryptoUtil;
import org.junit.Test;

import static com.hmdm.rest.resource.AgentEnrollTimeTest.TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * An agent whose enroll response was lost (Wi-Fi dropped during setup, the app stopped after the request) retries
 * with the same enroll attempt id and gets the device the first request created, instead of "code already used" and
 * an orphan device, or a second device from a batch code.
 */
public class AgentEnrollRetryTest {

    private static final String ATTEMPT = "Zq3v8yK1mN0pR5tW7xA2bC";

    private final AgentEnrollTimeTest.FakeTokenDAO tokens = new AgentEnrollTimeTest.FakeTokenDAO();
    private final AgentEnrollTimeTest.FakeCommandDAO commands = new AgentEnrollTimeTest.FakeCommandDAO();
    private final AgentResource resource = new AgentResource(new AgentEnrollTimeTest.FakeUnsecureDAO(), tokens,
            commands, AgentEnrollTimeTest.noConfigApps(), null, null, null, null, null);

    private Response enroll(String attempt) {
        AgentEnrollRequest req = new AgentEnrollRequest();
        req.setEnrollToken(TOKEN);
        req.setEnrollAttempt(attempt);
        return resource.enroll(req);
    }

    private static AgentEnrollResponse data(Response r) {
        assertEquals(r.getMessage(), Response.ResponseStatus.OK, r.getStatus());
        return (AgentEnrollResponse) r.getData();
    }

    @Test
    public void a_retry_of_the_same_attempt_gets_the_same_device_with_a_new_secret() {
        AgentEnrollResponse first = data(enroll(ATTEMPT));

        AgentEnrollResponse retry = data(enroll(ATTEMPT));

        assertEquals(first.getDeviceId(), retry.getDeviceId());
        assertNotEquals("the lost secret is replaced", first.getDeviceSecret(), retry.getDeviceSecret());
        assertEquals("only the new secret authenticates", CryptoUtil.getSHA256String(retry.getDeviceSecret()),
                commands.secretHashes.get(first.getDeviceId()));
        assertEquals("one device", 1, commands.completed.size());
        assertEquals("one use", 1, tokens.token.getUseCount());
    }

    @Test
    public void a_retry_does_not_take_another_use_of_a_batch_code() {
        tokens.token.setMaxUses(5);
        data(enroll(ATTEMPT));
        data(enroll(ATTEMPT));
        data(enroll(ATTEMPT));

        assertEquals(1, tokens.token.getUseCount());
        assertEquals(1, commands.completed.size());
    }

    @Test
    public void another_attempt_with_a_used_single_use_code_is_refused() {
        data(enroll(ATTEMPT));

        Response other = enroll("Other0attempt0value0xyz");

        assertEquals("error.agent.token.used", other.getMessage());
    }

    @Test
    public void without_an_attempt_a_used_code_is_refused_as_before() {
        data(enroll(null));

        assertEquals("error.agent.token.used", enroll(null).getMessage());
    }

    @Test
    public void the_retry_window_closes_at_the_first_check_in() {
        AgentEnrollResponse first = data(enroll(ATTEMPT));
        commands.touchLastUpdate(first.getDeviceId()); // what an authenticated check-in does

        assertEquals("error.agent.token.used", enroll(ATTEMPT).getMessage());
        assertEquals(CryptoUtil.getSHA256String(first.getDeviceSecret()),
                commands.secretHashes.get(first.getDeviceId()));
    }

    @Test
    public void a_stopped_code_is_not_retried() {
        AgentEnrollResponse first = data(enroll(ATTEMPT));
        tokens.token.setRevokedAt(System.currentTimeMillis());

        assertEquals("error.agent.token.used", enroll(ATTEMPT).getMessage());
        assertEquals(CryptoUtil.getSHA256String(first.getDeviceSecret()),
                commands.secretHashes.get(first.getDeviceId()));
    }

    @Test
    public void a_code_that_expired_since_still_resumes_its_own_enrollment() {
        AgentEnrollResponse first = data(enroll(ATTEMPT));
        tokens.token.setExpiresAt(System.currentTimeMillis() - 1);

        assertEquals(first.getDeviceId(), data(enroll(ATTEMPT)).getDeviceId());
    }

    @Test
    public void a_retry_while_the_first_request_is_still_running_waits_instead_of_using_the_code() {
        // The first request created its device and has not completed it yet (a slow server; the agent timed out).
        String hash = AgentResource.enrollAttemptHash(1, ATTEMPT);
        commands.markEnrollAttempt("dev-in-progress", hash);
        tokens.token.setUseCount(1);
        tokens.token.setUsed(true);

        Response retry = enroll(ATTEMPT);

        assertEquals("error.agent.enrollment.inProgress", retry.getMessage());
        for (String terminal : new String[]{"token.used", "token.invalid", "token.expired", "enrollment.disabled"}) {
            assertTrue(!retry.getMessage().contains(terminal));
        }
    }

    @Test
    public void a_batch_code_retry_while_the_first_is_running_takes_no_second_use() {
        tokens.token.setMaxUses(5);
        commands.markEnrollAttempt("dev-in-progress", AgentResource.enrollAttemptHash(1, ATTEMPT));

        assertEquals("error.agent.enrollment.inProgress", enroll(ATTEMPT).getMessage());
        assertEquals(0, tokens.token.getUseCount());
        assertTrue(commands.completed.isEmpty());
    }

    @Test
    public void the_new_device_carries_its_attempt_before_its_enrollment_completes() {
        commands.completion = "throw"; // stop right after the device is created
        enroll(ATTEMPT);
        // It was marked (then removed with the failed enrollment).
        assertEquals(1, commands.deleted.size());
    }

    @Test
    public void a_resumed_enrollment_does_not_look_checked_in() {
        String reissue = Fakes.sqlOf(AgentDeviceMapper.class, "reissueSecret").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(reissue, !reissue.contains("lastupdate"));
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

        data(enroll("short"));
        assertEquals("error.agent.token.used", enroll("short").getMessage());
    }

    @Test
    public void the_retry_lookup_and_reissue_are_scoped_to_the_attempt_and_a_recent_enrollment() {
        String find = Fakes.whereOf(Fakes.sqlOf(AgentDeviceMapper.class, "findEnrollRetry"));
        assertTrue(find, find.contains("agentenrollattempthash = #{attempthash}"));
        assertTrue(find, find.contains("enrolltime >= #{since}"));
        String reissue = Fakes.sqlOf(AgentDeviceMapper.class, "reissueSecret").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(reissue, reissue.contains("set agentsecrethash = #{secrethash}"));
        assertTrue(reissue, Fakes.whereOf(reissue).contains("agentenrollattempthash = #{attempthash}"));
        String progress = Fakes.whereOf(Fakes.sqlOf(AgentDeviceMapper.class, "isEnrollInProgress"));
        assertTrue(progress, progress.contains("agentenrollattempthash = #{attempthash}"));
        assertTrue(progress, progress.contains("agentsecrethash is null"));
        String mark = Fakes.sqlOf(AgentDeviceMapper.class, "markEnrollAttempt").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(mark, mark.contains("set agentenrollattempthash = #{attempthash}"));
        assertTrue(mark, Fakes.whereOf(mark).contains("agentsecrethash is null"));
        String checkIn = Fakes.sqlOf(AgentDeviceMapper.class, "markCheckedIn").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(checkIn, checkIn.contains("agentenrollattempthash = null"));
        String complete = Fakes.sqlOf(AgentDeviceMapper.class, "completeEnrollment").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(complete, complete.contains("agentenrollattempthash = #{attempthash}"));
    }
}
