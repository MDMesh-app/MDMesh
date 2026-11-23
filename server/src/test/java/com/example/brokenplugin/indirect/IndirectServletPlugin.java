package com.example.brokenplugin.indirect;

import com.example.brokenplugin.BrokenPluginConfiguration;
import com.example.brokenplugin.lib.LibraryServletModule;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.servlet.ServletModule;

import java.util.Arrays;
import java.util.List;

/**
 * A plugin that installs a ServletModule subclass on its own and, from inside another module, a subclass of it and a
 * subclass of a library's ServletModule, for PublicRouteInventoryTest. Neither of the last two extends ServletModule
 * itself, and both must be reported.
 */
public class IndirectServletPlugin {

    public List<Module> modules() {
        return Arrays.<Module>asList(new BaseRestModule(), new AbstractModule() {
            @Override
            protected void configure() {
                install(new SubRestModule());
                install(new LibrarySubRestModule());
            }
        });
    }

    public static class BaseRestModule extends ServletModule {
        @Override
        protected void configureServlets() {
            serve("/rest/plugins/demo/public/base").with(BrokenPluginConfiguration.BrokenServlet.class);
        }
    }

    public static class SubRestModule extends BaseRestModule {
        @Override
        protected void configureServlets() {
            serve("/rest/plugins/demo/sub").with(BrokenPluginConfiguration.BrokenServlet.class);
        }
    }

    public static class LibrarySubRestModule extends LibraryServletModule {
        @Override
        protected void configureServlets() {
            serve("/rest/plugins/demo/library").with(BrokenPluginConfiguration.BrokenServlet.class);
        }
    }
}
