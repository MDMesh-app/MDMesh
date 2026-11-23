/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Stopping every enrollment code: who may, what the endpoint stops, and that a stopped code enrolls nothing more. */
public class AgentEnrollTokenRevokeTest {

    private static final int CUSTOMER = 4;

    /** Records the customers whose tokens were stopped; answers 3 stopped. */
    private static final class RecordingTokenDAO extends AgentEnrollmentTokenDAO {
        final List<Integer> revokedFor = new ArrayList<>();

        RecordingTokenDAO() { super(null); }

        @Override public int revokeAll(int customerId) {
            revokedFor.add(customerId);
            return 3;
        }
    }

    private static AgentAdminResource admin(AgentEnrollmentTokenDAO tokens) {
        return new AgentAdminResource(tokens, null, null, null, null, null, null, null, null);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void stoppingAllCodesStopsTheSessionsCustomersAndSaysHowMany() {
        SecurityContext.init(Fakes.user(9, CUSTOMER, "edit_devices"));
        RecordingTokenDAO tokens = new RecordingTokenDAO();

        Response r = admin(tokens).revokeAllTokens();

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(3, ((Map<?, ?>) r.getData()).get("revoked"));
        assertEquals(Collections.singletonList(CUSTOMER), tokens.revokedFor);
    }

    @Test
    public void stoppingAllCodesNeedsTheRightToEditDevices() {
        // The same right as making one.
        SecurityContext.init(Fakes.user(9, CUSTOMER, "devices"));
        RecordingTokenDAO tokens = new RecordingTokenDAO();

        assertEquals(Response.ResponseStatus.ERROR, admin(tokens).revokeAllTokens().getStatus());
        assertTrue(tokens.revokedFor.isEmpty());
    }

    @Test
    public void aStoppedCodeEnrollsNothingAndAnOlderAgentStopsRetrying() {
        EnrollFixture f = new EnrollFixture();
        f.token.setMaxUses(10);
        assertEquals(Response.ResponseStatus.OK, f.enroll(null).getStatus());

        f.token.setRevokedAt(System.currentTimeMillis());
        Response refused = f.enroll(null);

        // token.used: every agent treats it as final (CheckInWorker's terminal markers); an unknown key is retried.
        assertEquals("error.agent.token.used", refused.getMessage());
        assertEquals("only the enroll before the stop used the code", 1, f.token.getUseCount());
    }

    @Test
    public void aClaimRefusesAStoppedToken() {
        String claim = Fakes.whereOf(Fakes.sqlOf(AgentEnrollmentTokenMapper.class, "claim"));
        assertTrue(claim, claim.contains("revokedat is null"));
    }

    @Test
    public void revokeAllStopsEveryUnexpiredTokenOfTheCustomerUsedUpOrNot() {
        String sql = Fakes.sqlOf(AgentEnrollmentTokenMapper.class, "revokeAll").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.contains("set revokedat = #{now}"));
        String where = Fakes.whereOf(sql);
        assertTrue(where, where.contains("customerid = #{customerid}"));
        assertTrue(where, where.contains("revokedat is null"));
        assertTrue(where, where.contains("expiresat is null or expiresat > #{now}"));
        // A used-up token is stopped too: a release after a failed enroll gives it a use back.
        assertFalse(where, where.contains("used"));
    }
}
