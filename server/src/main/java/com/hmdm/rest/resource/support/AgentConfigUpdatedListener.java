package com.hmdm.rest.resource.support;

import com.hmdm.event.ConfigurationUpdatedEvent;
import com.hmdm.event.EventListener;
import com.hmdm.event.EventType;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.util.ExecutorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Configuration saved → nudge its agent-v1 devices to check in now. Reconciliation itself happens in the
 * check-in (single code path); this only shortens the wait from the 15-min floor to seconds.
 */
public class AgentConfigUpdatedListener implements EventListener<ConfigurationUpdatedEvent> {
    private static final Logger logger = LoggerFactory.getLogger(AgentConfigUpdatedListener.class);
    private final AgentCommandDAO commandDAO;
    private final AgentWakeHub wakeHub;

    /**
     * {@code ConfigurationDAO.updateConfiguration} fires the event INSIDE its {@code @Transactional} method and
     * {@code EventService} dispatches asynchronously, so an immediate wake could make a device check in before
     * the configuration row is committed — it would then reconcile against the OLD revision and sit at the
     * 15-min floor. Delaying the wake by a short fixed interval lets the commit land first. Best-effort: a missed
     * wake only costs latency, never correctness (the next regular check-in reconciles).
     */
    static final long WAKE_DELAY_MS = 1500L;
    private final ScheduledExecutorService scheduler = ExecutorRegistry.register(
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-config-wake");
                t.setDaemon(true);
                return t;
            }));

    public AgentConfigUpdatedListener(AgentCommandDAO commandDAO, AgentWakeHub wakeHub) {
        this.commandDAO = commandDAO;
        this.wakeHub = wakeHub;
    }

    @Override
    public void onEvent(ConfigurationUpdatedEvent event) {
        try {
            scheduler.schedule(() -> wakeDevices(event), WAKE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // e.g. RejectedExecutionException during shutdown — never propagate into the event bus.
            logger.warn("could not schedule wake after configuration {} update", event.getConfigurationId(), e);
        }
    }

    private void wakeDevices(ConfigurationUpdatedEvent event) {
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
