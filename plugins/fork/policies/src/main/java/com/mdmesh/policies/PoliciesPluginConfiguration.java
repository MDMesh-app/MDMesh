package com.mdmesh.policies;

import com.google.inject.Module;
import com.hmdm.plugin.PluginConfiguration;
import com.hmdm.plugin.PluginTaskModule;
import com.mdmesh.policies.guice.PoliciesLiquibaseModule;
import com.mdmesh.policies.guice.PoliciesPersistenceModule;
import com.mdmesh.policies.guice.PoliciesRestModule;
import com.mdmesh.policies.guice.PoliciesTaskModule;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The Policies plugin: the server side of the console's Policies extension (same id, {@code policies}). */
public class PoliciesPluginConfiguration implements PluginConfiguration {

    public static final String PLUGIN_ID = "policies";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getRootPackage() {
        return "com.mdmesh.policies";
    }

    @Override
    public List<Module> getPluginModules(ServletContext context) {
        List<Module> modules = new ArrayList<>();
        modules.add(new PoliciesLiquibaseModule(context));
        modules.add(new PoliciesPersistenceModule(context));
        // Returned here itself (not installed from another module): core's PrivateRouteFilterTest reads its routes.
        modules.add(new PoliciesRestModule());
        return modules;
    }

    @Override
    public Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        List<Class<? extends PluginTaskModule>> tasks = new ArrayList<>();
        tasks.add(PoliciesTaskModule.class);
        return Optional.of(tasks);
    }
}
