package com.mdmesh.policies.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Core's wake service ({@code AgentWakeHub}): tells a connected phone to check in now. It lives in the server module,
 * which a plugin can't compile against, so it is reached by name through its public {@code INSTANCE} (core sets it at
 * boot for the same reason: its WebSocket endpoint isn't built by Guice).
 *
 * <p>If it can't be found, {@link #available()} is false: jobs still queue their commands, and phones pick them up at
 * their next regular check-in instead of at once. Slower, never lost.</p>
 */
@Singleton
public class CoreWake {
    private static final Logger log = LoggerFactory.getLogger(CoreWake.class);

    static final String HUB = "com.hmdm.notification.AgentWakeHub";

    private final Field instance;
    private final Method isOnline;
    private final Method wake;

    public CoreWake() {
        this(HUB);
    }

    CoreWake(String className) {
        Field f = null;
        Method online = null;
        Method w = null;
        try {
            Class<?> type = Class.forName(className, true, CoreWake.class.getClassLoader());
            f = type.getField("INSTANCE");
            online = type.getMethod("isOnline", String.class);
            w = type.getMethod("wake", String.class, String.class);
        } catch (Throwable e) {
            log.warn("Policies plugin: core's wake service ({}) is not reachable, so job phones get their commands at "
                    + "their next check-in: {}", className, e.toString());
            f = null;
        }
        this.instance = f;
        this.isOnline = f == null ? null : online;
        this.wake = f == null ? null : w;
    }

    private Object hub() {
        if (instance == null) return null;
        try {
            return instance.get(null);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public boolean available() {
        return hub() != null;
    }

    /** Whether the phone has a live connection, so a wake reaches it now. */
    public boolean isOnline(String number) {
        Object hub = hub();
        if (hub == null) return false;
        try {
            return Boolean.TRUE.equals(isOnline.invoke(hub, number));
        } catch (Exception e) {
            return false;
        }
    }

    /** Asks a connected phone to check in now (core's own "commands" wake). */
    public void wake(String number) {
        Object hub = hub();
        if (hub == null) return;
        try {
            wake.invoke(hub, number, "commands");
        } catch (Exception e) {
            log.debug("Policies plugin: wake for {} failed: {}", number, e.toString());
        }
    }
}
