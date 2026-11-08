package com.example.brokenplugin.nested;

import com.example.brokenplugin.BrokenPluginConfiguration;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.servlet.ServletModule;

import java.util.Arrays;
import java.util.List;

/**
 * A plugin whose modules, as getPluginModules() would return them, are a ServletModule and an AbstractModule that
 * installs a second, nested one, both anonymous and in this one file, for PublicRouteInventoryTest. Only the first is
 * a ServletModule the server installs on its own, so only its servlets would be checked: the nested one's must be
 * reported.
 */
public class NestedServletPlugin {

    public List<Module> modules() {
        return Arrays.<Module>asList(
                new ServletModule() {
                    @Override
                    protected void configureServlets() {
                        serve("/rest/plugins/demo/public/ok").with(BrokenPluginConfiguration.BrokenServlet.class);
                    }
                },
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        install(new ServletModule() {
                            @Override
                            protected void configureServlets() {
                                serve("/rest/plugins/demo/hidden").with(BrokenPluginConfiguration.BrokenServlet.class);
                            }
                        });
                    }
                });
    }
}
