/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.persistence;

import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.PgDatabase;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Account writes on real PostgreSQL, with a second connection standing in for another admin:
 * <ul>
 *     <li>a name and email change (/details, a customer's admin) never writes back the role and access it read before
 *     another admin's demotion committed;</li>
 *     <li>a write waits for the row lock another admin's change holds, then checks the account as that change left it
 *     (SELECT ... FOR UPDATE, then the re-check);</li>
 *     <li>shared mode: an access row naming another customer's group or configuration reaches nothing.</li>
 * </ul>
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set ({@link PgDatabase}).</p>
 */
public class UserAccountWritesPgTest {

    private static final int PLAIN_USER = 3;

    @ClassRule
    public static final PgDatabase DB = new PgDatabase("acct_it_");

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void aNameChangeKeepsADemotionCommittedAfterTheAccountWasRead() throws Exception {
        int customer = DB.customer();
        int x = DB.user(customer, PgDatabase.ORG_ADMIN, true);
        UserMapper users = DB.get(UserMapper.class);
        User read = users.findById(x);                       // what /details read, before the demotion
        SecurityContext.init(read);

        DB.sql("UPDATE users SET userRoleId = " + PLAIN_USER + ", allDevicesAvailable = false, "
                + "allConfigAvailable = false WHERE id = " + x);
        DB.get(UserDAO.class).updateUserIdentity(x, null, "Renamed", read.getEmail());

        assertEquals("Renamed|" + PLAIN_USER + "|false|false",
                DB.text("SELECT name || '|' || userRoleId || '|' || allDevicesAvailable || '|' || allConfigAvailable "
                        + "FROM users WHERE id = " + x));
    }

    @Test
    public void aWriteWaitsForAnotherAdminsChangeAndChecksTheAccountItLeft() throws Exception {
        int customer = DB.customer();
        int lobby = group(customer, "lobby");
        int admin = DB.user(customer, PgDatabase.ORG_ADMIN, false);
        int target = DB.user(customer, PLAIN_USER, false);
        DB.sql("INSERT INTO userDeviceGroupsAccess (userId, groupId) VALUES (" + admin + ", " + lobby + "), ("
                + target + ", " + lobby + ")");
        User session = DB.get(UserMapper.class).findById(admin);

        try (Connection other = DB.connect()) {
            // Another admin widens the account, and holds its row until it commits
            other.setAutoCommit(false);
            try (Statement s = other.createStatement()) {
                s.execute("SELECT id FROM users WHERE id = " + target + " FOR UPDATE");
                s.execute("UPDATE users SET allDevicesAvailable = true WHERE id = " + target);
            }
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread write = new Thread(() -> {
                SecurityContext.init(session);
                try {
                    DB.get(UserDAO.class).updateUserIdentity(target, null, "renamed",
                            "t" + target + "@example.test");
                    outcome.set(new AssertionError("written"));
                } catch (Throwable t) {
                    outcome.set(t);
                } finally {
                    SecurityContext.release();
                }
            });
            write.start();
            write.join(1500);
            assertTrue("the write must wait for the other admin's lock", write.isAlive());
            other.commit();
            write.join(15000);
            assertFalse(write.isAlive());
            assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof com.hmdm.security.SecurityException);
        }
        assertFalse("renamed".equals(DB.text("SELECT name FROM users WHERE id = " + target)));
    }

    @Test
    public void anAccessRowNamingAnotherCustomersGroupOrConfigurationReachesNothing() throws Exception {
        int mine = DB.customer();
        int theirs = DB.customer();
        int ownGroup = group(mine, "own");
        int foreignGroup = group(theirs, "foreign");
        int ownConfiguration = DB.configuration(mine, "own");
        int foreignConfiguration = DB.configuration(theirs, "foreign");
        int u = DB.user(mine, PLAIN_USER, false);
        DB.sql("UPDATE users SET allConfigAvailable = false WHERE id = " + u,
                "INSERT INTO userDeviceGroupsAccess (userId, groupId) VALUES (" + u + ", " + ownGroup + "), (" + u
                        + ", " + foreignGroup + ")",
                "INSERT INTO userConfigurationAccess (userId, configurationId) VALUES (" + u + ", " + ownConfiguration
                        + "), (" + u + ", " + foreignConfiguration + ")");

        User read = DB.get(UserMapper.class).findById(u);

        assertEquals(Collections.singletonList(ownGroup),
                read.getGroups().stream().map(LookupItem::getId).collect(Collectors.toList()));
        assertEquals(Collections.singletonList(ownConfiguration),
                read.getConfigurations().stream().map(LookupItem::getId).collect(Collectors.toList()));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static int group(int customerId, String name) throws Exception {
        return DB.scalar("INSERT INTO groups (name, customerId) VALUES ('" + name + "', " + customerId
                + ") RETURNING id");
    }
}
