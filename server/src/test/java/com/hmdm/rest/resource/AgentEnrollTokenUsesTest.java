/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import static com.hmdm.rest.resource.AgentEnrollTimeTest.TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A token for several devices, through the enroll endpoint and in the SQL that counts its uses. */
public class AgentEnrollTokenUsesTest {

    private static Response enroll(AgentResource resource) {
        AgentEnrollRequest req = new AgentEnrollRequest();
        req.setEnrollToken(TOKEN);
        return resource.enroll(req);
    }

    private static AgentResource resource(AgentEnrollTimeTest.FakeUnsecureDAO unsecure,
                                          AgentEnrollTimeTest.FakeTokenDAO tokens) {
        return new AgentResource(unsecure, tokens, new AgentEnrollTimeTest.FakeCommandDAO(),
                AgentEnrollTimeTest.noConfigApps(), null, null, null, null, null);
    }

    @Test
    public void a_token_for_two_devices_enrolls_two_and_refuses_a_third() {
        AgentEnrollTimeTest.FakeTokenDAO tokens = new AgentEnrollTimeTest.FakeTokenDAO();
        tokens.token.setMaxUses(2);
        AgentResource resource = resource(new AgentEnrollTimeTest.FakeUnsecureDAO(), tokens);

        assertEquals(Response.ResponseStatus.OK, enroll(resource).getStatus());
        assertFalse("one use left", tokens.token.isUsed());
        assertEquals(Response.ResponseStatus.OK, enroll(resource).getStatus());
        assertTrue("no use left", tokens.token.isUsed());

        Response third = enroll(resource);
        assertEquals(Response.ResponseStatus.ERROR, third.getStatus());
        assertEquals("error.agent.token.used", third.getMessage());
        assertEquals(2, tokens.token.getUseCount());
    }

    @Test
    public void a_refused_enroll_gives_its_use_back() {
        AgentEnrollTimeTest.FakeUnsecureDAO unsecure = new AgentEnrollTimeTest.FakeUnsecureDAO();
        AgentEnrollTimeTest.FakeTokenDAO tokens = new AgentEnrollTimeTest.FakeTokenDAO();
        tokens.token.setMaxUses(2);
        AgentResource resource = resource(unsecure, tokens);

        assertEquals(Response.ResponseStatus.OK, enroll(resource).getStatus());
        unsecure.creationEnabled = false;
        assertEquals("error.agent.enrollment.disabled", enroll(resource).getMessage());
        assertEquals("the refused enroll's use is given back", 1, tokens.token.getUseCount());

        unsecure.creationEnabled = true;
        assertEquals(Response.ResponseStatus.OK, enroll(resource).getStatus());
        assertTrue(tokens.token.isUsed());
    }

    @Test
    public void claiming_takes_one_use_only_while_one_is_left_and_marks_the_last() {
        // The guard that keeps concurrent enrolls from creating more devices than the token allows is this one
        // statement; scripts/agent-v1-e2e.sh runs it against PostgreSQL, in parallel.
        String sql = Fakes.sqlOf(AgentEnrollmentTokenMapper.class, "claim").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.contains("set usecount = usecount + 1, used = (usecount + 1 >= maxuses)"));
        String where = Fakes.whereOf(sql);
        assertTrue(where, where.contains("used = false"));
        assertTrue(where, where.contains("usecount < maxuses"));
        assertTrue(where, where.contains("expiresat is null or expiresat > #{now}"));
    }

    @Test
    public void releasing_gives_one_use_back() {
        String sql = Fakes.sqlOf(AgentEnrollmentTokenMapper.class, "release").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.contains("set usecount = greatest(usecount - 1, 0), used = false"));
    }
}
