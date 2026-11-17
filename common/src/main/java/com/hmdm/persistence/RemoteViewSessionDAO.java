package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.RemoteViewSession;
import com.hmdm.persistence.mapper.RemoteViewSessionMapper;
import java.util.UUID;
import org.mybatis.guice.transactional.Transactional;

/** Persistence boundary for remote-view metadata; signaling and media never cross it. */
@Singleton
public class RemoteViewSessionDAO {
    private final RemoteViewSessionMapper mapper;
    @Inject public RemoteViewSessionDAO(RemoteViewSessionMapper mapper) { this.mapper = mapper; }
    @Transactional public void insert(RemoteViewSession session, String detail) {
        mapper.insert(session);
        mapper.insertAudit(session.getId(), session.getState(), session.getCreatedAt(), session.getRequesterUserId(), detail);
    }
    public RemoteViewSession find(UUID id) { return mapper.find(id); }
    @Transactional public boolean transition(UUID id, String fromState, String toState, long at, Integer actor, String detail) {
        if (mapper.transition(id, fromState, toState) != 1) return false;
        mapper.insertAudit(id, toState, at, actor, detail);
        return true;
    }
    @Transactional public boolean end(UUID id, String state, long at, String reason) {
        RemoteViewSession session = mapper.find(id);
        if (session == null || mapper.end(id, state, at, reason) != 1) return false;
        mapper.insertAudit(id, state, at, session.getRequesterUserId(), reason);
        return true;
    }
    @Transactional public int expire(long now, String reason) {
        int ended = 0;
        for (RemoteViewSession session : mapper.findExpired(now)) {
            if (mapper.end(session.getId(), "expired", now, reason) == 1) {
                mapper.insertAudit(session.getId(), "expired", now, session.getRequesterUserId(), reason);
                ended++;
            }
        }
        return ended;
    }
    @Transactional public int endForDevice(String deviceNumber, String state, long at, String reason) {
        int ended = 0;
        for (RemoteViewSession session : mapper.findActiveByDevice(deviceNumber)) {
            if (mapper.end(session.getId(), state, at, reason) == 1) {
                mapper.insertAudit(session.getId(), state, at, session.getRequesterUserId(), reason);
                ended++;
            }
        }
        return ended;
    }
    @Transactional public int endForUser(int userId, String state, long at, String reason) {
        return endAll(mapper.findActiveByUser(userId), state, at, reason);
    }
    /** A server restart loses the in-memory WebSocket relay, so no active session can resume safely. */
    @Transactional public int endAllActive(String state, long at, String reason) {
        return endAll(mapper.findActive(), state, at, reason);
    }
    private int endAll(java.util.List<RemoteViewSession> sessions, String state, long at, String reason) {
        int ended = 0;
        for (RemoteViewSession session : sessions) {
            if (mapper.end(session.getId(), state, at, reason) == 1) {
                mapper.insertAudit(session.getId(), state, at, session.getRequesterUserId(), reason);
                ended++;
            }
        }
        return ended;
    }
}
