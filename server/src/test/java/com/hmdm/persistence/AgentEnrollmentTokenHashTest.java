/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Enrollment tokens are stored as their SHA-256, like device secrets: the database never holds a code that enrolls. */
public class AgentEnrollmentTokenHashTest {

    /** SHA-256("abc"), in the uppercase hex the 26.11.08 migration writes for an existing token. */
    private static final String ABC_HASH = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD";

    @Test
    public void theHashIsUppercaseHexSha256AsTheMigrationWritesIt() {
        assertEquals(ABC_HASH, AgentEnrollmentTokenDAO.hash("abc"));
    }

    @Test
    public void insertStoresTheHashAndHandsBackThePlaintext() {
        String[] stored = new String[1];
        FakeMapper<AgentEnrollmentTokenMapper> mapper = new FakeMapper<>(AgentEnrollmentTokenMapper.class)
                .answer("insert", a -> {
                    stored[0] = ((AgentEnrollmentToken) a[0]).getToken();
                    return null;
                });
        AgentEnrollmentToken token = new AgentEnrollmentToken();
        token.setToken("abc");

        new AgentEnrollmentTokenDAO(mapper.mapper).insert(token);

        assertEquals(ABC_HASH, stored[0]);
        assertEquals("the mint returns the code itself, once", "abc", token.getToken());
    }

    @Test
    public void aTokenIsLookedUpByItsHash() {
        AgentEnrollmentToken row = new AgentEnrollmentToken();
        FakeMapper<AgentEnrollmentTokenMapper> mapper = new FakeMapper<>(AgentEnrollmentTokenMapper.class)
                .answer("findByToken", a -> ABC_HASH.equals(a[0]) ? row : null);
        AgentEnrollmentTokenDAO dao = new AgentEnrollmentTokenDAO(mapper.mapper);

        assertSame(row, dao.findByToken("abc"));
        assertNull("the stored hash itself is not a code", dao.findByToken(ABC_HASH));
        assertNull(dao.findByToken(null));
    }
}
