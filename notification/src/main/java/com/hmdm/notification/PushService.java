package com.hmdm.notification;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.hmdm.notification.persistence.domain.PushMessage;
import com.hmdm.notification.persistence.mapper.NotificationMapper;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.filter.LegacyDeviceApiFilter;
import org.mybatis.guice.transactional.Transactional;

import java.util.List;

/**
 * <p>Queues push messages for the Headwind launcher (MQTT, or the long-poll queue in {@code pushMessages} /
 * {@code pendingPushes}). Only the launcher reads them: MDMesh agents are woken over their WebSocket. So while the
 * legacy device API ({@code legacy.device.api}) is off, every method here is a no-op and touches no table.</p>
 */
@Singleton
public class PushService {

    private final PushSender pushSenderMqtt;
    private final PushSender pushSenderPolling;
    private final ConfigurationDAO configurationDAO;
    private final DeviceDAO deviceDAO;
    private final boolean enabled;

    @Inject
    public PushService(@Named("MQTT") PushSender pushSenderMqtt, @Named("Polling") PushSender pushSenderPolling,
                       ConfigurationDAO configurationDAO, DeviceDAO deviceDAO,
                       @Named(LegacyDeviceApiFilter.PARAMETER) boolean legacyDeviceApi) {
        this.pushSenderMqtt = pushSenderMqtt;
        this.pushSenderPolling = pushSenderPolling;
        this.configurationDAO = configurationDAO;
        this.deviceDAO = deviceDAO;
        this.enabled = legacyDeviceApi;
    }

    /** True when messages are queued at all, i.e. the legacy device API is on. */
    public boolean isEnabled() {
        return enabled;
    }

    // Use both ways to send a message, because the decision how to receive messages is done on the device (configuration)
    public int send(PushMessage message) {
        if (!enabled) {
            return 0;
        }
        pushSenderMqtt.send(message);
        return pushSenderPolling.send(message);
    }

    /**
     * <p>Sends the messages on configuration update for the devices related to specified configuration.</p>
     *
     * @param configurationId an ID of updated configuration.
     */
    @Transactional
    public void notifyDevicesOnUpdate(Integer configurationId) {
        if (!enabled) {
            return;
        }
        final Configuration configuration = this.configurationDAO.getConfigurationById(configurationId);
        if (configuration != null) {
            final List<Device> devices
                    = this.deviceDAO.getDeviceIdsByConfigurationId(configurationId);
            devices.forEach(device -> {
                PushMessage message = new PushMessage();
                message.setDeviceId(device.getId());
                message.setMessageType(PushMessage.TYPE_CONFIG_UPDATED);

                this.send(message);
            });
        }
    }

    /**
     * <p>Sends the message on application settings update to specified device.</p>
     *
     * @param deviceId an ID of device to be notified.
     */
    @Transactional
    public void notifyDeviceOnSettingUpdate(Integer deviceId) {
        sendSimpleMessage(deviceId, PushMessage.TYPE_CONFIG_UPDATED);
    }

    /**
     * <p>Sends the message on application settings update to specified device.</p>
     *
     * @param deviceId an ID of device to be notified.
     */
    @Transactional
    public void notifyDeviceOnApplicationSettingUpdate(Integer deviceId) {
        sendSimpleMessage(deviceId, PushMessage.TYPE_APP_CONFIG_UPDATED);
    }

    /**
     * <p>Sends the simple message of a certain type to specified device.</p>
     *
     * @param deviceId an ID of device to be notified.
     * @param messageType Message type
     */
    public void sendSimpleMessage(Integer deviceId, String messageType) {
        if (!enabled) {
            return;
        }
        final Device dbDevice = this.deviceDAO.getDeviceById(deviceId);
        if (dbDevice != null) {
            PushMessage message = new PushMessage();
            message.setDeviceId(dbDevice.getId());
            message.setMessageType(messageType);

            this.send(message);
        }
    }
}
