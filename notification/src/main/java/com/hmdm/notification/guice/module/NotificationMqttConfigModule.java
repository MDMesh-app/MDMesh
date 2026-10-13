package com.hmdm.notification.guice.module;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;
import com.hmdm.rest.filter.LegacyDeviceApiFilter;
import org.apache.activemq.broker.BrokerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import java.security.SecureRandom;
import java.util.Base64;

public class NotificationMqttConfigModule extends AbstractModule {

    private static final Logger log = LoggerFactory.getLogger(NotificationMqttConfigModule.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * <p>A context for module execution.</p>
     */
    private final ServletContext context;

    /**
     * <p>Constructs new <code>PhotoConfigModule</code> instance. This implementation does nothing.</p>
     */
    public NotificationMqttConfigModule(ServletContext context) {
        this.context = context;
    }

    /**
     * <p>Configures the environment.</p>
     */
    protected void configure() {
        String serverUri = context.getInitParameter("mqtt.server.uri");
        if (serverUri == null) {
            serverUri = "";
        }
        String mqttExternal = context.getInitParameter("mqtt.external");
        if (mqttExternal == null || "".equals(mqttExternal)) {
            mqttExternal = "0";
        }
        boolean external = mqttExternal.equals("1") || mqttExternal.equalsIgnoreCase("true");
        String mqttAuthTag = this.context.getInitParameter("mqtt.auth");
        boolean mqttAuth = mqttAuthTag != null && (mqttAuthTag.equals("1") || mqttAuthTag.equalsIgnoreCase("true"));
        String mqttAdminPassword = context.getInitParameter("mqtt.admin.password");
        boolean passwordSet = mqttAdminPassword != null && !mqttAdminPassword.isEmpty();

        // Only the Headwind launcher speaks MQTT (the MDMesh agent wakes over its WebSocket), so the broker and the
        // MQTT push sender follow the legacy device API switch.
        if (!serverUri.isEmpty() && !LegacyDeviceApiFilter.isEnabled(context)) {
            log.info("MQTT not started: mqtt.server.uri is set but " + LegacyDeviceApiFilter.PARAMETER + " is off");
            serverUri = "";
        }
        // An external broker with authentication needs the password it was configured with: there is no default.
        if (!serverUri.isEmpty() && external && mqttAuth && !passwordSet) {
            log.error("MQTT not started: mqtt.external and mqtt.auth are on but mqtt.admin.password is not set");
            serverUri = "";
        }
        if (!passwordSet) {
            // Only the embedded broker and the server's own MQTT client use the password here, and both read this
            // binding, so a random one per start is enough.
            mqttAdminPassword = randomPassword();
        }

        // PushService queues launcher push messages only while the legacy device API is on.
        this.bindConstant().annotatedWith(Names.named(LegacyDeviceApiFilter.PARAMETER))
                .to(LegacyDeviceApiFilter.isEnabled(context));
        this.bindConstant().annotatedWith(Names.named("mqtt.server.uri")).to(serverUri);
        this.bindConstant().annotatedWith(Names.named("mqtt.external")).to(mqttExternal);

        String mqttClientTag = context.getInitParameter("mqtt.client.tag");
        if (mqttClientTag == null) {
            mqttClientTag = "";
        }
        this.bindConstant().annotatedWith(Names.named("mqtt.client.tag")).to(mqttClientTag);
        this.bindConstant().annotatedWith(Names.named("mqtt.auth")).to(mqttAuth);
        this.bindConstant().annotatedWith(Names.named("mqtt.admin.password")).to(mqttAdminPassword);

        String mqttDelayTag = this.context.getInitParameter("mqtt.message.delay");
        long mqttDelay = 0;
        try {
            if (mqttDelayTag != null) {
                mqttDelay = Long.parseLong(mqttDelayTag);
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        this.bindConstant().annotatedWith(Names.named("mqtt.message.delay")).to(mqttDelay);

        String pollTimeoutTag = this.context.getInitParameter("polling.timeout");
        long pollTimeout = 60;
        try {
            if (pollTimeoutTag != null) {
                pollTimeout = Long.parseLong(pollTimeoutTag);
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        this.bindConstant().annotatedWith(Names.named("polling.timeout")).to(pollTimeout);

    }

    private static String randomPassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
