package com.hmdm.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.service.RemoteViewSessionService;
import java.io.IOException;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.Session;
import com.hmdm.util.ExecutorRegistry;

/** In-memory SDP/ICE relay only. It never stores or logs signaling payloads or media. */
@Singleton
public final class RemoteViewSignalingHub {
    public static volatile RemoteViewSignalingHub INSTANCE;
    static final int MAX_MESSAGE_CHARS = 16 * 1024;
    private static final int MAX_MESSAGES_PER_MINUTE = 60;
    private static final int MAX_PREJOIN_MESSAGES = 32;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RemoteViewSessionService sessions;
    private final Map<UUID, Peers> peers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiry = ExecutorRegistry.register(Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "remote-view-signal-expiry"); t.setDaemon(true); return t;
    }));

    @Inject public RemoteViewSignalingHub(RemoteViewSessionService sessions) {
        this.sessions = sessions; INSTANCE = this;
        expiry.scheduleAtFixedRate(this::closeExpired, 5, 5, TimeUnit.SECONDS);
    }

    public boolean joinBrowser(UUID id, HttpSession http, Session socket) {
        if (http == null) return false;
        Object stored = http.getAttribute(AuthFilter.sessionCredentials);
        if (!(stored instanceof User)) return false;
        // WebSocket callbacks run outside the HTTP request thread, so they do not
        // have SecurityContext. The authenticated HTTP session is the authority
        // for this same-origin browser connection; do not call a context-bound DAO
        // from here.
        User current = (User) stored;
        if (current.isPasswordReset() || current.getAuthToken() == null || !hasRemoteView(current)
                || !sessions.browserMayJoin(id, current.getId(), System.currentTimeMillis())) return false;
        Peers pair = peers.computeIfAbsent(id, ignored -> new Peers());
        List<String> waiting;
        synchronized (pair) {
            if (pair.browser != null && pair.browser.isOpen()) return false;
            pair.browser = socket; pair.browserUser = current.getId();
            waiting = pair.drainForBrowser();
        }
        sendAll(socket, waiting);
        return true;
    }

    public boolean joinAgent(UUID id, String deviceNumber, String deviceSecret, String joinSecret, Session socket) {
        // AgentWakeHub is the single authoritative device-secret verifier; use its public API through the static hub.
        if (com.hmdm.notification.AgentWakeHub.INSTANCE == null
                || !com.hmdm.notification.AgentWakeHub.INSTANCE.validate(deviceNumber, deviceSecret)
                || sessions.claimAgentJoin(id, deviceNumber, joinSecret, System.currentTimeMillis()) == null) return false;
        Peers pair = peers.computeIfAbsent(id, ignored -> new Peers());
        List<String> waiting;
        synchronized (pair) {
            if (pair.agent != null && pair.agent.isOpen()) return false;
            pair.agent = socket; pair.deviceNumber = deviceNumber;
            waiting = pair.drainForAgent();
        }
        sendAll(socket, waiting);
        return true;
    }

    public void relay(UUID id, boolean fromBrowser, String text) {
        if (!sessions.isSignalingLive(id, System.currentTimeMillis())) { close(id, "session ended or expired"); return; }
        if (text == null || text.length() > MAX_MESSAGE_CHARS || !validEnvelope(text, fromBrowser)) { close(id, "invalid signaling message"); return; }
        Peers pair = peers.get(id);
        if (pair == null || !pair.permit()) { close(id, "rate limit"); return; }
        Session target;
        synchronized (pair) {
            target = fromBrowser ? pair.agent : pair.browser;
            if (target == null || !target.isOpen()) {
                pair.queueForTarget(fromBrowser, text);
                return;
            }
        }
        if (target != null && target.isOpen()) target.getAsyncRemote().sendText(text);
    }

    public void disconnect(UUID id, Session socket) {
        Peers pair = peers.get(id); if (pair == null) return;
        synchronized (pair) { if (pair.browser == socket) pair.browser = null; if (pair.agent == socket) pair.agent = null;
            if (pair.browser == null && pair.agent == null) peers.remove(id, pair); }
    }

    /** Ends the durable session when the only capture peer disconnects unexpectedly. */
    public void agentDisconnected(UUID id, Session socket, String reason) {
        Peers pair = peers.get(id);
        if (pair == null) return;
        synchronized (pair) {
            if (pair.agent != socket) return;
        }
        sessions.stop(id, System.currentTimeMillis(), reason);
    }
    public void close(UUID id, String reason) {
        Peers pair = peers.remove(id); if (pair == null) return;
        closeQuietly(pair.browser, reason); closeQuietly(pair.agent, reason);
    }
    public void closeDevice(String deviceNumber, String reason) {
        for (Map.Entry<UUID, Peers> entry : peers.entrySet()) {
            if (deviceNumber.equals(entry.getValue().deviceNumber)) close(entry.getKey(), reason);
        }
    }
    private void closeExpired() { for (UUID id : peers.keySet()) if (!sessions.isSignalingLive(id, System.currentTimeMillis())) close(id, "session ended or expired"); }
    private static boolean hasRemoteView(User user) {
        return user.isSuperAdmin() || (user.getUserRole() != null && user.getUserRole().getPermissions() != null
                && user.getUserRole().getPermissions().stream().anyMatch(p -> "remote_view".equalsIgnoreCase(p.getName())));
    }
    private static void sendAll(Session socket, List<String> messages) {
        if (socket == null || !socket.isOpen()) return;
        for (String message : messages) socket.getAsyncRemote().sendText(message);
    }
    static boolean validEnvelope(String text, boolean fromBrowser) {
        try {
            JsonNode root = JSON.readTree(text); if (!root.isObject() || root.size() != 2) return false;
            JsonNode type = root.get("type"), payload = root.get("payload"); if (type == null || !type.isTextual() || payload == null) return false;
            // The agent owns the screen sender and therefore creates the offer.
            // This avoids asking older Android WebRTC runtimes to consume a
            // browser-generated offer before they have established their local
            // media graph.
            String value = type.asText(); return fromBrowser ? ("answer".equals(value) || "candidate".equals(value)) : ("offer".equals(value) || "candidate".equals(value));
        } catch (IOException bad) { return false; }
    }
    private static void closeQuietly(Session socket, String reason) { if (socket != null && socket.isOpen()) try { socket.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason)); } catch (IOException ignored) {} }
    static final class Peers { Session browser, agent; Integer browserUser; String deviceNumber; long window = System.currentTimeMillis(); int count;
        private final Deque<String> waitingForBrowser = new ArrayDeque<>();
        private final Deque<String> waitingForAgent = new ArrayDeque<>();
        synchronized boolean permit() { long now = System.currentTimeMillis(); if (now - window >= 60_000L) { window = now; count = 0; } return ++count <= MAX_MESSAGES_PER_MINUTE; }
        void queueForTarget(boolean targetIsAgent, String message) {
            Deque<String> queue = targetIsAgent ? waitingForAgent : waitingForBrowser;
            if (queue.size() < MAX_PREJOIN_MESSAGES) queue.addLast(message);
        }
        List<String> drainForBrowser() { return drain(waitingForBrowser); }
        List<String> drainForAgent() { return drain(waitingForAgent); }
        private static List<String> drain(Deque<String> queue) { List<String> result = new ArrayList<>(queue); queue.clear(); return result; }
    }
}
