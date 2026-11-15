package com.hmdm.remote;

import com.hmdm.persistence.RemoteViewSessionDAO;
import com.hmdm.service.RemoteViewSessionService;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import javax.websocket.Session;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteViewSignalingHubTest {
    @Test public void acceptsOnlyViewOnlyWebRtcSignalingEnvelopesForTheirRole() {
        assertTrue(RemoteViewSignalingHub.validEnvelope("{\"type\":\"offer\",\"payload\":{\"sdp\":\"x\"}}", true));
        assertTrue(RemoteViewSignalingHub.validEnvelope("{\"type\":\"answer\",\"payload\":{\"sdp\":\"x\"}}", false));
        assertTrue(RemoteViewSignalingHub.validEnvelope("{\"type\":\"candidate\",\"payload\":{\"candidate\":\"x\"}}", true));
        assertFalse(RemoteViewSignalingHub.validEnvelope("{\"type\":\"answer\",\"payload\":{}}", true));
        assertFalse(RemoteViewSignalingHub.validEnvelope("{\"type\":\"control\",\"payload\":{}}", false));
        assertFalse(RemoteViewSignalingHub.validEnvelope("{\"type\":\"offer\",\"payload\":{},\"extra\":true}", true));
    }

    @Test public void agentDisconnectEndsOnlyTheSessionOwnedByThatAgentSocket() throws Exception {
        RecordingSessions sessions = new RecordingSessions();
        RemoteViewSignalingHub hub = new RemoteViewSignalingHub(sessions);
        UUID id = UUID.randomUUID();
        Session agent = socket();
        putAgentPeer(hub, id, agent);

        hub.agentDisconnected(id, socket(), "ignored socket");
        assertNull(sessions.stopped);

        hub.agentDisconnected(id, agent, "agent signaling closed");
        assertEquals(id, sessions.stopped);
        assertEquals("agent signaling closed", sessions.reason);
    }

    private static Session socket() {
        return (Session) Proxy.newProxyInstance(RemoteViewSignalingHubTest.class.getClassLoader(),
                new Class<?>[]{Session.class}, (proxy, method, args) -> null);
    }

    @SuppressWarnings("unchecked")
    private static void putAgentPeer(RemoteViewSignalingHub hub, UUID id, Session agent) throws Exception {
        Field peers = RemoteViewSignalingHub.class.getDeclaredField("peers"); peers.setAccessible(true);
        Class<?> pairType = Class.forName("com.hmdm.remote.RemoteViewSignalingHub$Peers");
        java.lang.reflect.Constructor<?> constructor = pairType.getDeclaredConstructor(); constructor.setAccessible(true);
        Object pair = constructor.newInstance();
        Field agentField = pairType.getDeclaredField("agent"); agentField.setAccessible(true); agentField.set(pair, agent);
        ((Map<UUID, Object>) peers.get(hub)).put(id, pair);
    }

    private static final class RecordingSessions extends RemoteViewSessionService {
        UUID stopped; String reason;
        RecordingSessions() { super(new RemoteViewSessionDAO(null) {
            @Override public int expire(long now, String reason) { return 0; }
        }); }
        @Override public boolean stop(UUID id, long now, String reason) { stopped = id; this.reason = reason; return true; }
    }
}
