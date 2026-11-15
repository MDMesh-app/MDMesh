package com.hmdm.rest;

import com.hmdm.remote.RemoteViewSignalingHub;
import java.util.UUID;
import javax.servlet.http.HttpSession;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Browser-side peer of {@link RemoteViewSignalingEndpoint}. */
@ServerEndpoint(value = "/agent/viewer/{sessionId}", configurator = RemoteViewSignalingEndpoint.Auth.class)
public class RemoteViewBrowserSignalingEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(RemoteViewBrowserSignalingEndpoint.class);
    private static final String HTTP_SESSION = "remote.httpSession";

    @OnOpen public void open(Session socket, @PathParam("sessionId") String raw) {
        UUID id = RemoteViewSignalingEndpoint.id(raw);
        RemoteViewSignalingHub hub = RemoteViewSignalingHub.INSTANCE;
        boolean allowed = id != null && hub != null
                && hub.joinBrowser(id, (HttpSession) socket.getUserProperties().get(HTTP_SESSION), socket);
        if (!allowed) {
            logger.warn("Remote-view browser signaling rejected: session={}", raw);
            RemoteViewSignalingEndpoint.close(socket, "unauthorized");
        } else {
            logger.info("Remote-view browser signaling joined: session={}", raw);
        }
    }

    @OnMessage public void message(Session socket, String text, @PathParam("sessionId") String raw) {
        UUID id = RemoteViewSignalingEndpoint.id(raw);
        if (id == null || RemoteViewSignalingHub.INSTANCE == null) {
            RemoteViewSignalingEndpoint.close(socket, "invalid session");
            return;
        }
        RemoteViewSignalingHub.INSTANCE.relay(id, true, text);
    }

    @OnClose public void closed(Session socket, @PathParam("sessionId") String raw) {
        UUID id = RemoteViewSignalingEndpoint.id(raw);
        if (id != null && RemoteViewSignalingHub.INSTANCE != null) {
            RemoteViewSignalingHub.INSTANCE.disconnect(id, socket);
        }
    }

    @javax.websocket.OnError public void error(Session socket, @PathParam("sessionId") String raw, Throwable failure) {
        logger.warn("Remote-view browser signaling error: session={}, type={}", raw,
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }
}
