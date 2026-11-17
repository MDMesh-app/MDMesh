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

    @Test public void agentJoinSecretIsBoundToDeviceAndCannotBeReplayed() {
        FakeDao dao = new FakeDao(); RemoteViewSessionService service = new RemoteViewSessionService(dao);
        RemoteViewSessionService.Created created = service.create("device-1", 7, 1000L);
        assertNull(service.claimAgentJoin(created.session.getId(), "other-device", created.agentJoinSecret, 1001L));
        assertNotNull(service.claimAgentJoin(created.session.getId(), "device-1", created.agentJoinSecret, 1001L));
        assertNull(service.claimAgentJoin(created.session.getId(), "device-1", created.agentJoinSecret, 1002L));
        assertTrue(service.isSignalingLive(created.session.getId(), 1002L));
    }

    @Test public void authenticatedAgentEventsAdvanceAndEndOnlyTheirOwnSession() {
        FakeDao dao = new FakeDao(); RemoteViewSessionService service = new RemoteViewSessionService(dao);
        RemoteViewSessionService.Created created = service.create("device-1", 7, 1000L);
        String detail = "session=" + created.session.getId();
        service.recordAgentEvent("other-device", "remoteViewConsentRequested", detail, 1001L);
        assertEquals("requested", dao.sessions.get(created.session.getId()).getState());
        service.recordAgentEvent("device-1", "remoteViewConsentRequested", detail, 1002L);
        assertEquals("awaiting_consent", dao.sessions.get(created.session.getId()).getState());
        assertNotNull(service.claimAgentJoin(created.session.getId(), "device-1", created.agentJoinSecret, 1003L));
        service.recordAgentEvent("device-1", "remoteViewEnded", detail + "; reason=projection stopped", 1004L);
        assertEquals("stopped", dao.sessions.get(created.session.getId()).getState());
        assertEquals(Long.valueOf(1004L), dao.sessions.get(created.session.getId()).getEndedAt());
    }

    @Test public void deniedEventEndsSessionAndMalformedDetailIsIgnored() {
        FakeDao dao = new FakeDao(); RemoteViewSessionService service = new RemoteViewSessionService(dao);
        RemoteViewSessionService.Created created = service.create("device-1", 7, 1000L);
        service.recordAgentEvent("device-1", "remoteViewDenied", "not-a-session", 1001L);
        assertEquals("requested", dao.sessions.get(created.session.getId()).getState());
        service.recordAgentEvent("device-1", "remoteViewDenied", "session=" + created.session.getId(), 1002L);
        assertEquals("denied", dao.sessions.get(created.session.getId()).getState());
    }

    @Test public void browserMayJoinOnlyItsOwnersLiveSignalingSession() {
        FakeDao dao = new FakeDao(); RemoteViewSessionService service = new RemoteViewSessionService(dao);
        RemoteViewSessionService.Created created = service.create("device-1", 7, 1000L);
        assertFalse(service.browserMayJoin(created.session.getId(), 7, 1001L));
        assertFalse(service.browserMayJoin(created.session.getId(), 8, 1001L));
        assertNotNull(service.claimAgentJoin(created.session.getId(), "device-1", created.agentJoinSecret, 1002L));
        assertTrue(service.browserMayJoin(created.session.getId(), 7, 1003L));
        assertSame(created.session, service.findOwned(created.session.getId(), 7));
        assertNull(service.findOwned(created.session.getId(), 8));
    }

    @Test public void startupEndsAllPreviouslyActiveSessionsBecauseTheirRelayCannotResume() {
        FakeDao dao = new FakeDao();
        new RemoteViewSessionService(dao);
        assertEquals(1, dao.endAllActiveCalls);
        assertEquals("server restarted", dao.endAllActiveReason);
    }

    private static final class FakeDao extends RemoteViewSessionDAO {
        final Map<UUID, RemoteViewSession> sessions = new HashMap<>();
        int endAllActiveCalls; String endAllActiveReason;
        FakeDao() { super(null); }
        @Override public void insert(RemoteViewSession session, String detail) { sessions.put(session.getId(), session); }
        @Override public RemoteViewSession find(UUID id) { return sessions.get(id); }
        @Override public boolean end(UUID id, String state, long at, String reason) {
            RemoteViewSession s = sessions.get(id); if (s == null || s.getEndedAt() != null) return false;
            s.setState(state); s.setEndedAt(at); s.setEndReason(reason); return true;
        }
        @Override public boolean transition(UUID id, String from, String to, long at, Integer actor, String detail) {
            RemoteViewSession s = sessions.get(id); if (s == null || !from.equals(s.getState())) return false; s.setState(to); return true;
        }
        @Override public int expire(long now, String reason) { return 0; }
        @Override public int endForDevice(String device, String state, long at, String reason) { return 0; }
        @Override public int endAllActive(String state, long at, String reason) {
            endAllActiveCalls++; endAllActiveReason = reason; return 0;
        }
        @Override public int endForUser(int userId, String state, long at, String reason) { return 0; }
    }
}
