package com.hmdm.rest;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Extension;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import org.junit.Test;

import static org.junit.Assert.assertFalse;

/** Regression coverage for the agent WebSocket, which has no servlet HTTP session. */
public class RemoteViewSignalingEndpointTest {
    @Test
    public void sessionlessAgentHandshakeDoesNotPutNullIntoTomcatProperties() {
        ServerEndpointConfig config = new TestEndpointConfig();

        new RemoteViewSignalingEndpoint.Auth().modifyHandshake(config, new SessionlessRequest(), null);

        assertFalse(config.getUserProperties().containsKey("remote.httpSession"));
    }

    private static final class SessionlessRequest implements HandshakeRequest {
        @Override public Map<String, java.util.List<String>> getHeaders() { return Collections.emptyMap(); }
        @Override public Principal getUserPrincipal() { return null; }
        @Override public URI getRequestURI() { return URI.create("/agent/remote/test"); }
        @Override public boolean isUserInRole(String role) { return false; }
        @Override public Object getHttpSession() { return null; }
        @Override public Map<String, java.util.List<String>> getParameterMap() { return Collections.emptyMap(); }
        @Override public String getQueryString() { return null; }
    }

    /** Mirrors Tomcat's null-rejecting user-properties map without needing a servlet container. */
    private static final class TestEndpointConfig implements ServerEndpointConfig {
        private final Map<String, Object> userProperties = new ConcurrentHashMap<>();
        @Override public Class<?> getEndpointClass() { return RemoteViewSignalingEndpoint.class; }
        @Override public String getPath() { return "/agent/remote/{sessionId}"; }
        @Override public List<String> getSubprotocols() { return Collections.emptyList(); }
        @Override public List<Extension> getExtensions() { return Collections.emptyList(); }
        @Override public Configurator getConfigurator() { return new Configurator(); }
        @Override public List<Class<? extends Encoder>> getEncoders() { return new ArrayList<>(); }
        @Override public List<Class<? extends Decoder>> getDecoders() { return new ArrayList<>(); }
        @Override public Map<String, Object> getUserProperties() { return userProperties; }
    }
}
