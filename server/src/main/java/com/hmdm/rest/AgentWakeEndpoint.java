/*
 * MDMesh agent wake channel — JSR-356 WebSocket endpoint.
 *
 * The device holds one wss connection here (over the same tunnel as the REST API); the server
 * pushes a tiny "sync now" signal when work is queued. The device then pulls + acks over the
 * authenticated /checkin endpoint. Container-managed (not Guice) — it reaches the session registry
 * via AgentWakeHub.INSTANCE.
 *
 * Auth: the per-device secret is presented as an {@code Authorization: Bearer} header on the
 * handshake (never in the URL — query strings leak into access logs). A handshake configurator
 * copies that header into the session's user properties for {@link #onOpen} to validate.
 */
package com.hmdm.rest;

import com.hmdm.notification.AgentWakeHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.CloseReason;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@ServerEndpoint(value = "/agent/ws/{deviceNumber}", configurator = AgentWakeEndpoint.AuthConfigurator.class)
public class AgentWakeEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AgentWakeEndpoint.class);
    private static final String SECRET_PROP = "mdm.wake.secret";

    /** Copies the Authorization bearer token off the handshake into the session's user properties. */
    public static final class AuthConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
            String bearer = bearer(headerIgnoreCase(request.getHeaders(), "Authorization"));
            // Per-connection: the new session's user properties are seeded from this map.
            if (bearer != null) {
                config.getUserProperties().put(SECRET_PROP, bearer);
            } else {
                config.getUserProperties().remove(SECRET_PROP);
            }
        }
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("deviceNumber") String deviceNumber) {
        AgentWakeHub hub = AgentWakeHub.INSTANCE;
        Object secret = session.getUserProperties().get(SECRET_PROP);
        // validate() checks the secret against THIS device's stored hash, so a mismatched/raced
        // secret simply fails closed (the client reconnects) — it can never authenticate as another device.
        if (hub == null || !(secret instanceof String) || !hub.validate(deviceNumber, (String) secret)) {
            closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, "unauthorized");
            return;
        }
        hub.register(deviceNumber, session);
    }

    @OnClose
    public void onClose(Session session, @PathParam("deviceNumber") String deviceNumber) {
        if (AgentWakeHub.INSTANCE != null) {
            AgentWakeHub.INSTANCE.unregister(deviceNumber, session);
        }
    }

    @OnError
    public void onError(Session session, @PathParam("deviceNumber") String deviceNumber, Throwable t) {
        log.debug("Agent wake socket error for {}: {}", deviceNumber, t.getMessage());
        if (AgentWakeHub.INSTANCE != null) {
            AgentWakeHub.INSTANCE.unregister(deviceNumber, session);
        }
    }

    private static String headerIgnoreCase(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) {
                List<String> v = e.getValue();
                return (v == null || v.isEmpty()) ? null : v.get(0);
            }
        }
        return null;
    }

    private static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String s = authorization.trim();
        if (s.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = s.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private static void closeQuietly(Session session, CloseReason.CloseCodes code, String reason) {
        try {
            session.close(new CloseReason(code, reason));
        } catch (IOException ignored) {
        }
    }
}
