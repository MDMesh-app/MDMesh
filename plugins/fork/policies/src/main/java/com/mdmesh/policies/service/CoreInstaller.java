package com.mdmesh.policies.service;

import com.google.inject.Injector;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Device;
import com.mdmesh.policies.persistence.domain.JobCommand;
import com.mdmesh.policies.persistence.domain.JobPhone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Core's "install the policy's apps" ({@code ConfigAppInstaller}, behind the device page's Install config apps),
 * reached by name like {@link CoreStatus}. Core builds each install command (with its APK checksum, or as an already
 * failed command saying why); the job only stores them and paces the wakes. Unavailable: the install action is refused.
 */
@Singleton
public class CoreInstaller {
    private static final Logger log = LoggerFactory.getLogger(CoreInstaller.class);

    static final String INSTALLER = "com.hmdm.rest.resource.support.ConfigAppInstaller";

    private final Object installer;
    private final Method installableApps;
    private final Method installCommand;

    @Inject
    public CoreInstaller(Injector injector) {
        this(injector::getInstance, INSTALLER);
    }

    CoreInstaller(Function<Class<?>, Object> instances, String className) {
        Object inst = null;
        Method apps = null;
        Method cmd = null;
        try {
            Class<?> type = Class.forName(className, true, CoreInstaller.class.getClassLoader());
            apps = type.getMethod("installableApps", Device.class);
            cmd = type.getMethod("installCommand", Device.class, Application.class, long.class);
            inst = instances.apply(type);
        } catch (Throwable e) {
            log.warn("Policies plugin: core's app installer ({}) is not reachable, so Install policy apps runs per phone: {}",
                    className, e.toString());
            inst = null;
        }
        this.installer = inst;
        this.installableApps = inst == null ? null : apps;
        this.installCommand = inst == null ? null : cmd;
    }

    public boolean available() {
        return installer != null;
    }

    /** The install commands of one phone's policy apps, as core builds them. An app core fails on is left out. */
    @SuppressWarnings("unchecked")
    public List<JobCommand> commands(JobPhone phone, long now) {
        if (!available() || phone.getConfigurationId() == null) return Collections.emptyList();
        Device device = new Device();
        device.setId(phone.getId());
        device.setNumber(phone.getNumber());
        device.setConfigurationId(phone.getConfigurationId());
        device.setCustomerId(phone.getCustomerId());
        List<JobCommand> out = new ArrayList<>();
        List<Application> apps;
        try {
            apps = (List<Application>) installableApps.invoke(installer, device);
        } catch (Exception e) {
            log.warn("Policies plugin: no installable apps for {}: {}", phone.getNumber(), cause(e));
            return out;
        }
        for (Application app : apps) {
            try {
                AgentCommand c = (AgentCommand) installCommand.invoke(installer, device, app, now);
                JobCommand j = new JobCommand(c.getDeviceNumber(), c.getType(), c.getPayload(), c.getRequiresCapability(), now);
                j.setStatus(c.getStatus());
                j.setDetail(c.getDetail());
                j.setCompletedAt(c.getCompletedAt());
                out.add(j);
            } catch (Exception e) {
                log.warn("Policies plugin: install of {} on {} not queued: {}", app.getPkg(), phone.getNumber(), cause(e));
            }
        }
        return out;
    }

    private static String cause(Exception e) {
        Throwable t = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        return t.toString();
    }
}
