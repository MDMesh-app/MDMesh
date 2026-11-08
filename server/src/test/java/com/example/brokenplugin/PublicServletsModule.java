package com.example.brokenplugin;

import com.google.inject.servlet.ServletModule;

/**
 * Two public servlets, served as a fork plugin's ServletModule usually serves them, for PublicRouteInventoryTest: the
 * check reads them from the module's bindings, so neither the unqualified call nor the pattern built at run time can
 * hide them.
 */
public class PublicServletsModule extends ServletModule {

    static final String FEED = "/rest/plugins/demo/feed/";

    @Override
    protected void configureServlets() {
        serve("/rest/plugins/demo/upload").with(BrokenPluginConfiguration.BrokenServlet.class);
        this.serve(FEED + "*").with(BrokenPluginConfiguration.BrokenServlet.class);
    }
}
