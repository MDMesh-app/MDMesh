package com.hmdm.rest;

import com.hmdm.remote.RemoteViewSignalingHub;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent-side remote-view signaling endpoint. No media, commands, or credentials are relayed.
 *
 * <p>The peer role is deliberately fixed in the endpoint path. Besides being a smaller authority
 * surface than a role path parameter, this avoids an ambiguous template mapping in the Tomcat
 * deployment used by the native installer.</p>
 */
@ServerEndpoint(value = "/agent/remote/{sessionId}", configurator = RemoteViewSignalingEndpoint.Auth.class)
public class RemoteViewSignalingEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(RemoteViewSignalingEndpoint.class);
    private static final String HTTP_SESSION = "remote.httpSession";
    private static final String AUTH = "remote.authorization";
    private static final String JOIN = "remote.joinSecret";
    private static final String DEVICE = "remote.device";
    public static final class Auth extends ServerEndpointConfig.Configurator {
        @Override public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
            String authorization = header(request.getHeaders(), "Authorization");
            String join = header(request.getHeaders(), "X-MDMesh-Remote-Session");
            String device = header(request.getHeaders(), "X-MDMesh-Device");
            config.getUserProperties().put(HTTP_SESSION, request.getHttpSession());
            put(config, AUTH, authorization);
            put(config, JOIN, join);
            put(config, DEVICE, device);
            // Handshake diagnostics are intentionally presence-only: bearer and join secrets never reach logs.
            logger.info("Remote-view handshake: path={}, authorization={}, device={}, joinSecret={}",
                    request.getRequestURI().getPath(), authorization != null, device != null, join != null);
        }
        private static void put(ServerEndpointConfig c, String key, String value) { if (value != null) c.getUserProperties().put(key, value); }
    }
    @OnOpen public void open(Session socket, @PathParam("sessionId") String raw) {
        UUID id = id(raw); RemoteViewSignalingHub hub = RemoteViewSignalingHub.INSTANCE;
        boolean allowed = id != null && hub != null
                && hub.joinAgent(id, string(socket, DEVICE), bearer(string(socket, AUTH)), string(socket, JOIN), socket);
        if (!allowed) {
            logger.warn("Remote-view agent signaling rejected: session={}", raw);
            close(socket, "unauthorized");
        } else {
            logger.info("Remote-view agent signaling joined: session={}", raw);
        }
    }
    @OnMessage public void message(Session socket, String text, @PathParam("sessionId") String raw) {
        UUID id = id(raw); if (id == null || RemoteViewSignalingHub.INSTANCE == null) { close(socket, "invalid session"); return; }
        RemoteViewSignalingHub.INSTANCE.relay(id, false, text);
    }
    @OnClose public void closed(Session socket, @PathParam("sessionId") String raw) { UUID id = id(raw); if (id != null && RemoteViewSignalingHub.INSTANCE != null) RemoteViewSignalingHub.INSTANCE.disconnect(id, socket); }
    @javax.websocket.OnError public void error(Session socket, @PathParam("sessionId") String raw, Throwable failure) {
        logger.warn("Remote-view signaling error: session={}, type={}", raw,
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }
    static UUID id(String raw) { try { return UUID.fromString(raw); } catch (Exception ignored) { return null; } }
    static String string(Session s, String key) { Object v=s.getUserProperties().get(key); return v instanceof String ? (String)v : null; }
    private static String header(Map<String,List<String>> headers, String wanted) { if(headers!=null) for(Map.Entry<String,List<String>> e:headers.entrySet()) if(wanted.equalsIgnoreCase(e.getKey()) && e.getValue()!=null && !e.getValue().isEmpty()) return e.getValue().get(0); return null; }
    private static String bearer(String value) { if(value == null || !value.regionMatches(true,0,"Bearer ",0,7)) return null; String token=value.substring(7).trim(); return token.isEmpty()?null:token; }
    static void close(Session socket, String message) { try { socket.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, message)); } catch (IOException ignored) {} }
}
