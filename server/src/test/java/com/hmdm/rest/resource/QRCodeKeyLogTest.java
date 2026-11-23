/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.util.CryptoUtil;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The QR-code key fetches the provisioning QR, which holds the Wi-Fi password: the QR routes log a short fingerprint
 * of it (or the configuration's ID), never the key.
 */
public class QRCodeKeyLogTest {

    private static final String MISSING = "qr-key-missing-1";
    private static final String NO_MAIN_APP = "qr-key-no-main-app-2";
    private static final String BROKEN = "qr-key-broken-3";

    private final List<String> logged = new ArrayList<>();
    private final AppenderSkeleton capture = new AppenderSkeleton() {
        @Override
        protected void append(LoggingEvent event) {
            logged.add(event.getRenderedMessage());
            if (event.getThrowableStrRep() != null) {
                logged.add(String.join("\n", event.getThrowableStrRep()));
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    };
    private Level previous;

    @Before
    public void setUp() {
        Logger logger = Logger.getLogger(QRCodeResource.class);
        previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addAppender(capture);
    }

    @After
    public void tearDown() {
        Logger logger = Logger.getLogger(QRCodeResource.class);
        logger.removeAppender(capture);
        logger.setLevel(previous);
    }

    @Test
    public void theQrRoutesNeverLogTheKey() throws Exception {
        QRCodeResource resource = new QRCodeResource(configurations(), null, "/nonexistent-mdmesh-test-files",
                "https://mdm.example.test");
        for (String key : new String[]{MISSING, NO_MAIN_APP, BROKEN}) {
            resource.generateJSON(key, null, null, null, null, null);
            resource.generateQRCode(key, null, null, null, null, null, null);
        }

        assertFalse(logged.isEmpty());
        for (String line : logged) {
            for (String key : new String[]{MISSING, NO_MAIN_APP, BROKEN}) {
                assertFalse(line, line.contains(key));
            }
        }
        assertTrue(logged.toString(), String.join("\n", logged).contains(CryptoUtil.logFingerprint(MISSING)));
    }

    /** A DAO with no database: one key has no configuration, one a configuration with no main app, one fails. */
    private static UnsecureDAO configurations() {
        return new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                "/nonexistent-mdmesh-test-files", 2, "com.hmdm.launcher") {
            @Override
            public Configuration getLegacyConfigurationByQRCodeKey(String id) {
                if (BROKEN.equals(id)) {
                    throw new IllegalStateException("lookup failed for key " + CryptoUtil.logFingerprint(id));
                }
                if (NO_MAIN_APP.equals(id)) {
                    Configuration configuration = new Configuration();
                    configuration.setId(4);
                    return configuration;
                }
                return null;
            }
        };
    }
}
