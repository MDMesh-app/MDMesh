package com.example.brokenplugin;

import com.google.inject.Module;
import com.google.inject.servlet.ServletModule;
import com.hmdm.plugin.PluginConfiguration;
import com.hmdm.plugin.rest.PluginAccessFilter;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.json.Response;
import com.hmdm.security.jwt.JWTFilter;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.Collections;
import java.util.List;

/**
 * A fork plugin that gets its private routes' filters wrong, one way per route, for PrivateRouteFilterTest. It is
 * deliberately outside {@code com.hmdm} and {@code com.mdmesh}: neither the plugin scan nor Jersey finds it, so it
 * never reaches the server's own checks, and the test hands it over by hand.
 */
public class BrokenPluginConfiguration implements PluginConfiguration {

    public static final String ID = "broken";

    @Override
    public String getPluginId() {
        return ID;
    }

    @Override
    public String getRootPackage() {
        return "com.example.brokenplugin";
    }

    @Override
    public List<Module> getPluginModules(ServletContext context) {
        return Collections.<Module>singletonList(new BrokenRestModule());
    }

    /** One route per mistake, and one done right. */
    public static class BrokenRestModule extends ServletModule {
        @Override
        protected void configureServlets() {
            // A typo: the pattern names another path, so /typo goes through neither filter.
            filter("/rest/plugins/broken/private/typp/*").through(JWTFilter.class);
            filter("/rest/plugins/broken/private/typp/*").through(AuthFilter.class);
            filter("/rest/plugins/broken/private/typp/*").through(PluginAccessFilter.class);
            // AuthFilter left out.
            filter("/rest/plugins/broken/private/noauth").through(PluginAccessFilter.class);
            // PluginAccessFilter left out.
            filter("/rest/plugins/broken/private/noaccess").through(JWTFilter.class);
            filter("/rest/plugins/broken/private/noaccess").through(AuthFilter.class);
            // Both, the wrong way round: PluginAccessFilter reads the user's customer, which AuthFilter sets.
            filter("/rest/plugins/broken/private/reversed").through(PluginAccessFilter.class);
            filter("/rest/plugins/broken/private/reversed").through(AuthFilter.class);
            // Right.
            filter("/rest/plugins/broken/private/ok").through(JWTFilter.class);
            filter("/rest/plugins/broken/private/ok").through(AuthFilter.class);
            filter("/rest/plugins/broken/private/ok").through(PluginAccessFilter.class);
            // A servlet below a private segment, with no filter at all.
            serve("/rest/plugins/broken/private/upload").with(BrokenServlet.class);
        }
    }

    /** A servlet served by a regex, which no route check can match a route against. */
    public static class RegexRestModule extends ServletModule {
        @Override
        protected void configureServlets() {
            filter("/rest/plugins/broken/private/*").through(AuthFilter.class);
            serveRegex("/rest/plugins/broken/private/regex/.*").with(BrokenServlet.class);
        }
    }

    @Path("/plugins/broken/private")
    public static class BrokenResource {
        @GET @Path("/typo/{id}") public Response typo() { return Response.OK(); }
        @GET @Path("/noauth") public Response noAuth() { return Response.OK(); }
        @GET @Path("/noaccess") public Response noAccess() { return Response.OK(); }
        @GET @Path("/reversed") public Response reversed() { return Response.OK(); }
        @GET @Path("/ok") public Response ok() { return Response.OK(); }
    }

    public static class BrokenServlet extends HttpServlet {
    }
}
