package com.mdmesh.policies.guice;

import com.google.inject.servlet.ServletModule;
import com.hmdm.plugin.rest.PluginAccessFilter;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.filter.PrivateIPFilter;
import com.hmdm.security.jwt.JWTFilter;
import com.mdmesh.policies.rest.AutomationResource;
import com.mdmesh.policies.rest.FleetResource;
import com.mdmesh.policies.rest.GroupResource;
import com.mdmesh.policies.rest.JobResource;
import com.mdmesh.policies.rest.ScheduleResource;

import java.util.Collections;
import java.util.List;

/** The plugin's REST: private routes only, signed in and only for customers the plugin is on for. */
public class PoliciesRestModule extends ServletModule {

    private static final List<String> PRIVATE = Collections.singletonList("/rest/plugins/policies/private/*");

    @Override
    protected void configureServlets() {
        filter(PRIVATE).through(JWTFilter.class);
        filter(PRIVATE).through(AuthFilter.class);
        filter(PRIVATE).through(PluginAccessFilter.class);
        filter(PRIVATE).through(PrivateIPFilter.class);
        bind(FleetResource.class);
        bind(JobResource.class);
        bind(AutomationResource.class);
        bind(GroupResource.class);
        bind(ScheduleResource.class);
    }
}
