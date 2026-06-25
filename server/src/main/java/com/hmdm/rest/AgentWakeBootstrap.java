/*
 * Registers the agent wake WebSocket endpoint programmatically.
 *
 * The app's web.xml is a legacy Servlet 2.3 descriptor, so Tomcat does not auto-scan @ServerEndpoint
 * classes. The WebSocket SCI still initializes the JSR-356 ServerContainer for the context, so we
 * register the endpoint against it at context startup. Declared as a <listener> in web.xml.
 */
package com.hmdm.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.server.ServerContainer;

public class AgentWakeBootstrap implements ServletContextListener {

    private static final Logger log = LoggerFactory.getLogger(AgentWakeBootstrap.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        Object attr = sce.getServletContext().getAttribute("javax.websocket.server.ServerContainer");
        if (!(attr instanceof ServerContainer)) {
            log.error("Agent wake: no JSR-356 ServerContainer in context — wake channel disabled");
            return;
        }
        try {
            ((ServerContainer) attr).addEndpoint(AgentWakeEndpoint.class);
            log.info("Agent wake WebSocket endpoint registered at /agent/ws/{deviceNumber}");
        } catch (Exception e) {
            log.error("Agent wake: failed to register WebSocket endpoint", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
    }
}
