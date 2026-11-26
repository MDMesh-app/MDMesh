package com.mdmesh.policies.service;

import com.google.inject.Injector;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.function.Function;

/**
 * Core's own "is this phone's configuration applied" logic, reached at runtime.
 *
 * <p>It lives in the server module ({@code ConfigReconciler}), which a plugin can't compile against (the server
 * depends on the plugins). Copying it would let the plugin quietly drift from core, so it is looked up by name instead:
 * the same instance core's own status endpoints use. If a future core renames it, {@link #available()} is false, a
 * warning is logged once, and the fleet answer says so, so the console falls back to core's per-phone status calls.
 * Slower, never wrong.</p>
 */
@Singleton
public class CoreStatus {
    private static final Logger log = LoggerFactory.getLogger(CoreStatus.class);

    static final String RECONCILER = "com.hmdm.rest.resource.support.ConfigReconciler";

    private final Object reconciler;
    private final Method statusDocument;
    private final Method statusRevision;

    @Inject
    public CoreStatus(Injector injector) {
        this(injector::getInstance, RECONCILER);
    }

    /** Looks up {@code className} and gets its instance from {@code instances} (a test passes a stand-in). */
    CoreStatus(Function<Class<?>, Object> instances, String className) {
        Object instance = null;
        Method document = null;
        Method revision = null;
        try {
            Class<?> type = Class.forName(className, true, CoreStatus.class.getClassLoader());
            document = type.getMethod("statusDocument", Device.class);
            revision = type.getMethod("statusRevision", DesiredConfig.class, Set.class);
            instance = instances.apply(type);
        } catch (Throwable e) {
            log.warn("Policies plugin: core's configuration status ({}) is not reachable, so the console checks each "
                    + "phone on its own: {}", className, e.toString());
            instance = null;
        }
        this.reconciler = instance;
        this.statusDocument = instance == null ? null : document;
        this.statusRevision = instance == null ? null : revision;
    }

    /** Whether core's status logic was found. */
    public boolean available() {
        return reconciler != null;
    }

    /**
     * The settings document of a configuration, as core builds it for its status endpoints; null when the
     * configuration has none, or when core fails on it (core logs that itself).
     */
    public DesiredConfig document(int customerId, int configurationId) {
        if (!available()) return null;
        Device probe = new Device();
        probe.setConfigurationId(configurationId);
        probe.setCustomerId(customerId);
        return (DesiredConfig) call(statusDocument, reconciler, probe);
    }

    /** The revision a phone with these capability tokens should report for {@code doc} (null for no document). */
    public String revision(DesiredConfig doc, Set<String> deviceTokens) {
        if (!available() || doc == null) return null;
        return (String) call(statusRevision, null, doc, deviceTokens);
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
