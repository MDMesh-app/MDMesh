/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.task;

import com.hmdm.persistence.CommonDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.PasswordUtil;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** At startup the server warns while the account named admin still signs in with the password admin, once seeded. */
public class DefaultAdminPasswordCheckTest {

    /** MD5("admin"), as the clients send it. */
    private static final String ADMIN_MD5 = "21232F297A57A5A743894A0E4A801FC3";

    private final List<LoggingEvent> logged = new ArrayList<>();
    private final AppenderSkeleton capture = new AppenderSkeleton() {
        @Override
        protected void append(LoggingEvent event) {
            logged.add(event);
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
    private User admin;
    /** The shared-mode super-admin, seeded with admin's password at the time (changeset 16.01.19-16:10). */
    private User superadmin;
    /** Whether the seed has run: only the seed fills the settings table. */
    private boolean seeded = true;
    private DefaultAdminPasswordCheck check;

    @Before
    public void setUp() {
        Logger logger = Logger.getLogger(DefaultAdminPasswordCheck.class);
        previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addAppender(capture);
        Fakes.FakeMapper<UserMapper> users = new Fakes.FakeMapper<>(UserMapper.class);
        users.answer("findByLogin", a -> "admin".equals(a[0]) ? admin : "superadmin".equals(a[0]) ? superadmin : null);
        check = new DefaultAdminPasswordCheck(new UnsecureDAO(null, users.mapper, null, null, null, null, null, null,
                null, null, null, null, null, null, ".", 2, "com.hmdm.launcher"), new CommonDAO(null, null, null) {
            @Override
            public boolean isDatabaseInitialized() {
                return seeded;
            }
        });
    }

    @After
    public void tearDown() {
        Logger logger = Logger.getLogger(DefaultAdminPasswordCheck.class);
        logger.removeAppender(capture);
        logger.setLevel(previous);
    }

    @Test
    public void warnsForTheDefaultPasswordStoredAsBcrypt() {
        admin = admin(PasswordUtil.getHashFromRaw("admin"));
        check.run();
        assertWarned();
    }

    @Test
    public void warnsForTheDefaultPasswordStoredInTheLegacyFormat() {
        admin = admin(CryptoUtil.getSHA1String(ADMIN_MD5 + "5YdSYHyg2U"));
        check.run();
        assertWarned();
    }

    @Test
    public void staysQuietOnAFreshInstallsFirstBootBeforeTheSeed() {
        // The changelog's admin/admin row is there until the installer seeds, which it does after this first boot
        seeded = false;
        admin = admin(CryptoUtil.getSHA1String(ADMIN_MD5 + "5YdSYHyg2U"));
        check.run();
        assertTrue(logged.isEmpty());
    }

    @Test
    public void staysQuietOnceThePasswordIsChanged() {
        admin = admin(PasswordUtil.getHashFromRaw("something else"));
        check.run();
        assertTrue(logged.isEmpty());
    }

    @Test
    public void staysQuietWithoutAnAccountNamedAdmin() {
        admin = null;
        check.run();
        assertTrue(logged.isEmpty());
    }

    @Test
    public void warnsForASharedModeSuperAdminStillOnTheDefaultPassword() {
        admin = admin(PasswordUtil.getHashFromRaw("changed by the installer"));
        superadmin = admin(CryptoUtil.getSHA1String(ADMIN_MD5 + "5YdSYHyg2U"));
        superadmin.setLogin("superadmin");
        check.run();

        assertEquals(1, logged.size());
        String message = String.valueOf(logged.get(0).getRenderedMessage());
        assertTrue(message, message.contains("account 'superadmin'"));
    }

    @Test
    public void warnsOncePerAccountOnTheDefaultPassword() {
        admin = admin(PasswordUtil.getHashFromRaw("admin"));
        superadmin = admin(PasswordUtil.getHashFromRaw("admin"));
        superadmin.setLogin("superadmin");
        check.run();
        assertEquals(2, logged.size());
    }

    private void assertWarned() {
        assertEquals(1, logged.size());
        assertEquals(Level.WARN, logged.get(0).getLevel());
        String message = String.valueOf(logged.get(0).getRenderedMessage());
        assertTrue(message, message.contains("admin"));
        assertFalse(message, message.contains(admin.getPassword()));
    }

    private static User admin(String storedPassword) {
        User user = new User();
        user.setId(1);
        user.setLogin("admin");
        user.setPassword(storedPassword);
        return user;
    }
}
