package com.hmdm.notification;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.domain.AgentCommand;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the wake replay in {@link AgentWakeHub#register}: a socket that registers while commands are pending gets one
 * {@code {"wake":"commands"}} at once (a wake sent while the device was offline was dropped), one with nothing pending
 * gets nothing, and a failing pending-commands read never escapes. If it escaped {@code AgentWakeEndpoint.onOpen},
 * Tomcat would close the socket that had just authenticated. The DAO and the session are hand-made fakes (the
 * {@link Proxy} pattern PluginDiscoveryTest uses), so no database or container is needed.
 */
public class AgentWakeHubTest {

    private static final String DEVICE = "dev-1";

    private final List<String> sent = new ArrayList<>();
    private final List<LoggingEvent> warnings = new ArrayList<>();
    private boolean closed;
    private AppenderSkeleton capture;

    @Before
    public void captureWarnings() {
        capture = new AppenderSkeleton() {
            @Override
            protected void append(LoggingEvent event) {
                if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                    warnings.add(event);
                }
            }

            @Override
            public void close() {
            }

            @Override
            public boolean requiresLayout() {
                return false;
            }
        };
        Logger.getLogger(AgentWakeHub.class).addAppender(capture);
    }

    @After
    public void releaseWarnings() {
        Logger.getLogger(AgentWakeHub.class).removeAppender(capture);
    }

    /** An open session that records what is sent on it and whether it was closed. */
    private Session session() {
        RemoteEndpoint.Async async = (RemoteEndpoint.Async) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{RemoteEndpoint.Async.class},
                (proxy, method, args) -> {
                    if ("sendText".equals(method.getName())) {
                        sent.add((String) args[0]);
                    }
                    return null;
                });
        return (Session) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Session.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isOpen":
                            return !closed;
                        case "getAsyncRemote":
                            return async;
                        case "close":
                            closed = true;
                            return null;
                        default:
                            return null;
                    }
                });
    }

    /** A DAO whose pending-commands read returns {@code pending}, or throws when {@code pending} is null. */
    private static AgentCommandDAO dao(List<AgentCommand> pending) {
        return new AgentCommandDAO(null, null, null, null) {
            @Override
            public List<AgentCommand> listPending(String deviceNumber) {
                if (pending == null) {
                    throw new IllegalStateException("database unavailable");
                }
                return pending;
            }
        };
    }

    @Test
    public void registerWithPendingCommandsSendsOneCommandsWake() {
        AgentWakeHub hub = new AgentWakeHub(dao(Collections.singletonList(new AgentCommand())));

        hub.register(DEVICE, session());

        assertEquals(Collections.singletonList("{\"wake\":\"commands\"}"), sent);
        assertTrue(hub.isOnline(DEVICE));
    }

    @Test
    public void registerWithNothingPendingSendsNothing() {
        AgentWakeHub hub = new AgentWakeHub(dao(Collections.emptyList()));

        hub.register(DEVICE, session());

        assertTrue("unexpected wake: " + sent, sent.isEmpty());
        assertTrue(hub.isOnline(DEVICE));
    }

    @Test
    public void failingPendingCheckKeepsTheSessionAndLogsAWarning() {
        AgentWakeHub hub = new AgentWakeHub(dao(null));

        hub.register(DEVICE, session());

        assertFalse("the session was closed", closed);
        assertTrue("the device is no longer registered", hub.isOnline(DEVICE));
        assertTrue("unexpected wake: " + sent, sent.isEmpty());
        assertEquals("warnings logged", 1, warnings.size());
    }
}
