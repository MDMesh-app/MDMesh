/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.notification.guice.module;

import com.hmdm.util.ExecutorRegistry;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.hmdm.notification.persistence.NotificationDAO;
import com.hmdm.rest.filter.LegacyDeviceApiFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <p>A module used for starting the runnable tasks for <code>Notification</code> sub-system.</p>
 *
 * @author isv
 */
public class NotificationTaskModule {

    private final ScheduledExecutorService messagePurgeService = ExecutorRegistry.register(Executors.newScheduledThreadPool(1));

    private static final Logger log = LoggerFactory.getLogger(NotificationTaskModule.class);

    private final NotificationDAO notificationDAO;
    private final boolean legacyDeviceApi;

    /**
     * <p>Constructs new <code>NotificationTaskModule</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public NotificationTaskModule(NotificationDAO notificationDAO,
                                  @Named(LegacyDeviceApiFilter.PARAMETER) boolean legacyDeviceApi) {
        this.notificationDAO = notificationDAO;
        this.legacyDeviceApi = legacyDeviceApi;
    }

    public void init() {
        if (legacyDeviceApi) {
            messagePurgeService.scheduleWithFixedDelay(new MessagePurgeWorker(notificationDAO),
                    1, 1, TimeUnit.HOURS);
        } else {
            // Nothing is queued while the legacy device API is off, so run the purge once at startup: it deletes
            // messages past the usual limits (undelivered after 1 hour, delivered after 7 days) and keeps newer ones.
            log.info("Push message purge runs once: " + LegacyDeviceApiFilter.PARAMETER + " is off");
            messagePurgeService.schedule(new MessagePurgeWorker(notificationDAO), 1, TimeUnit.MINUTES);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(messagePurgeService::shutdown));
    }

    /**
     * <p>A task to delete the push messages with lifespans longer than pre-defined limits.</p>
     */
    public static class MessagePurgeWorker implements Runnable {
        private final static Logger log = LoggerFactory.getLogger(MessagePurgeWorker.class);
        private final NotificationDAO notificationDAO;

        public MessagePurgeWorker(NotificationDAO notificationDAO) {
            this.notificationDAO = notificationDAO;
        }

        @Override
        public void run() {
            log.info("Starting the iteration ...");
            try {
                this.notificationDAO.purgeMessages(3600, 7 * 24 * 3600);
                log.info("Finished the iteration.");
            } catch (Exception e) {
                log.error("Unexpected error when purging the push messages", e);
            }
        }
    }

}
