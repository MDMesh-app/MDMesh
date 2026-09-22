package com.hmdm.rest.resource.support;

import com.hmdm.event.ConfigurationUpdatedEvent;
import com.hmdm.event.EventListener;
import com.hmdm.event.EventType;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration saved → nudge its agent-v1 devices to check in now. Reconciliation itself happens in the
 * check-in (single code path); this only shortens the wait from the 15-min floor to seconds.
 */
public class AgentConfigUpdatedListener implements EventListener<ConfigurationUpdatedEvent> {
    private static final Logger logger = LoggerFactory.getLogger(AgentConfigUpdatedListener.class);
    private final AgentCommandDAO commandDAO;
    private final AgentWakeHub wakeHub;

    public AgentConfigUpdatedListener(AgentCommandDAO commandDAO, AgentWakeHub wakeHub) {
        this.commandDAO = commandDAO;
        this.wakeHub = wakeHub;
    }

    @Override
    public void onEvent(ConfigurationUpdatedEvent event) {
        try {
            for (String number : commandDAO.listDeviceNumbersByConfigurationId(event.getConfigurationId())) {
                wakeHub.wake(number, "commands");
            }
        } catch (Exception e) {
            logger.warn("wake after configuration {} update failed", event.getConfigurationId(), e);
        }
    }

    @Override
    public EventType getSupportedEventType() { return EventType.CONFIGURATION_UPDATED; }
}
