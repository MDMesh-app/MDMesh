package com.hmdm.service;

import com.hmdm.persistence.RemoteViewSessionDAO;
import com.hmdm.persistence.domain.RemoteViewSession;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class RemoteViewSessionServiceTest {
    @Test public void createsHashedSingleUseSecretAndOnlyOwnerCanStop() {
        FakeDao dao = new FakeDao();
        RemoteViewSessionService service = new RemoteViewSessionService(dao);
        RemoteViewSessionService.Created created = service.create("device-1", 7, 1000L);
        assertEquals("requested", created.session.getState());
        assertEquals(64, created.session.getAgentJoinSecretHash().length());
        assertEquals(64, created.agentJoinSecret.length());
        assertNotEquals(created.agentJoinSecret, created.session.getAgentJoinSecretHash());
        assertFalse(service.stopOwned(created.session.getId(), 8, 2000L));
        assertTrue(service.stopOwned(created.session.getId(), 7, 2000L));
        assertEquals("stopped", dao.sessions.get(created.session.getId()).getState());
    }

    private static final class FakeDao extends RemoteViewSessionDAO {
        final Map<UUID, RemoteViewSession> sessions = new HashMap<>();
        FakeDao() { super(null); }
        @Override public void insert(RemoteViewSession session, String detail) { sessions.put(session.getId(), session); }
        @Override public RemoteViewSession find(UUID id) { return sessions.get(id); }
        @Override public boolean end(UUID id, String state, long at, String reason) {
            RemoteViewSession s = sessions.get(id); if (s == null || s.getEndedAt() != null) return false;
            s.setState(state); s.setEndedAt(at); s.setEndReason(reason); return true;
        }
        @Override public int expire(long now, String reason) { return 0; }
        @Override public int endForDevice(String device, String state, long at, String reason) { return 0; }
    }
}
