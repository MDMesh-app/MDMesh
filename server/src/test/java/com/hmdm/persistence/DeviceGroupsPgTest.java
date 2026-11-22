/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A device the console adds or edits (DeviceDAO.insertDevice, updateDevice) goes only into groups of the user's own
 * customer that the user may use, all or nothing: one other group refuses the whole change and the device's groups stay
 * as they were. A group the device is already in may stay. On the real schema and mappers.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest for the docker one-liner); with
 * MDMESH_TEST_PG_REQUIRED it fails without one. Works in its own schema, dropped afterwards.</p>
 */
public class DeviceGroupsPgTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    private static String schema;
    private static DeviceDAO dao;
    private static int customer;
    private static int configuration;
    private static int open;          // the limited user may use it
    private static int closed;        // same customer; the limited user may not use it
    private static int foreign;       // another customer's
    private static User admin;        // every group of the customer
    private static User limited;      // only `open`

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "groups_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema);
        String url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection c = DriverManager.getConnection(url, user(), password())) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("liquibase/db.changelog.xml", new ClassLoaderResourceAccessor(), db).update("common,private");
        }
        Map<String, String> jdbc = new HashMap<>();
        jdbc.put("JDBC.driver", "org.postgresql.Driver");
        jdbc.put("JDBC.url", url);
        jdbc.put("JDBC.username", user());
        jdbc.put("JDBC.password", password());
        Injector injector = Guice.createInjector(new PersistenceModule(servletContext(jdbc)), new AbstractModule() {
            @Override
            protected void configure() {
                Properties props = new Properties();
                props.setProperty("files.directory", "/nonexistent-mdmesh-test-files");
                props.setProperty("role.orgadmin.id", "2");
                props.setProperty("launcher.package", "com.hmdm.launcher");
                props.setProperty("device.fast.search.chars", "5");
                props.setProperty("aapt.command", "aapt");
                props.setProperty("apk.trusted.url", "");
                props.setProperty("base.url", "https://mdm.example.test");
                props.setProperty("hash.secret", "test-hash-secret");
                Names.bindProperties(binder(), props);
            }
        });
        dao = injector.getInstance(DeviceDAO.class);

        customer = (int) scalar("INSERT INTO " + schema + ".customers (name, filesDir, prefix) "
                + "VALUES ('groups-a', 'groups-a', 'groups-a') RETURNING id");
        int other = (int) scalar("INSERT INTO " + schema + ".customers (name, filesDir, prefix) "
                + "VALUES ('groups-b', 'groups-b', 'groups-b') RETURNING id");
        configuration = (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId) VALUES ('c', "
                + customer + ") RETURNING id");
        open = (int) scalar("INSERT INTO " + schema + ".groups (name, customerId) VALUES ('open', " + customer
                + ") RETURNING id");
        closed = (int) scalar("INSERT INTO " + schema + ".groups (name, customerId) VALUES ('closed', " + customer
                + ") RETURNING id");
        foreign = (int) scalar("INSERT INTO " + schema + ".groups (name, customerId) VALUES ('foreign', " + other
                + ") RETURNING id");
        admin = user(true);
        limited = user(false);
        sql("INSERT INTO " + schema + ".userDeviceGroupsAccess (userId, groupId) VALUES (" + limited.getId() + ", "
                + open + ")");
    }

    /** A user of {@link #customer} with edit_devices, every configuration, and every group or only those granted. */
    private static User user(boolean allGroups) throws Exception {
        String login = "u" + UUID.randomUUID().toString().substring(0, 8);
        int id = (int) scalar("INSERT INTO " + schema + ".users (login, email, name, password, customerId, userRoleId, "
                + "allDevicesAvailable, allConfigAvailable) VALUES ('" + login + "', '" + login + "@example.test', '"
                + login + "', 'x', " + customer + ", 2, " + allGroups + ", true) RETURNING id");
        User u = Fakes.user(id, customer, "edit_devices", "devices");
        u.setAllDevicesAvailable(allGroups);
        return u;
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (schema != null) sql("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    public void anEditIntoAnotherCustomersGroupChangesNothing() throws Exception {
        int device = device(open);
        SecurityContext.init(admin);

        Device change = edit(device, open, foreign);
        refused(() -> dao.updateDevice(change));

        assertEquals(Collections.singletonList(open), groupsOf(device));
        assertEquals("unchanged", descriptionOf(device));
    }

    @Test
    public void anEditIntoAGroupTheUserMayNotUseChangesNothing() throws Exception {
        int device = device(open);
        SecurityContext.init(limited);

        Device change = edit(device, open, closed);
        refused(() -> dao.updateDevice(change));

        assertEquals(Collections.singletonList(open), groupsOf(device));
        assertEquals("unchanged", descriptionOf(device));
    }

    @Test
    public void anEditWithAGroupThatDoesNotExistChangesNothing() throws Exception {
        int device = device(open);
        SecurityContext.init(admin);

        Device change = edit(device, open, 2_000_000_000);
        refused(() -> dao.updateDevice(change));

        assertEquals(Collections.singletonList(open), groupsOf(device));
    }

    @Test
    public void anEditIntoTheUsersOwnGroupsIsSaved() throws Exception {
        int device = device(open);
        SecurityContext.init(admin);

        dao.updateDevice(edit(device, open, closed));

        assertEquals(Arrays.asList(open, closed), groupsOf(device));
        assertEquals("edited", descriptionOf(device));
    }

    @Test
    public void aGroupTheDeviceIsAlreadyInMayStay() throws Exception {
        // The limited user sees the device through `open`; `closed` is not theirs to add, but it is already there
        int device = device(open, closed);
        SecurityContext.init(limited);

        dao.updateDevice(edit(device, open, closed));
        dao.updateDevice(edit(device, open, closed));

        // One row per group however often it is saved: `closed` outlives the user's delete and is not added again
        assertEquals(Arrays.asList(open, closed), groupsOf(device));
        assertEquals("edited", descriptionOf(device));
    }

    @Test
    public void aGroupSentTwiceIsAddedOnce() throws Exception {
        int device = device();
        SecurityContext.init(admin);

        dao.updateDevice(edit(device, open, open, closed));

        assertEquals(Arrays.asList(open, closed), groupsOf(device));
    }

    @Test
    public void aNewDeviceIsNotAddedIntoAnotherCustomersGroup() throws Exception {
        SecurityContext.init(admin);
        Device d = new Device();
        String number = "new-" + UUID.randomUUID().toString().substring(0, 8);
        d.setNumber(number);
        d.setConfigurationId(configuration);
        d.setLastUpdate(0L);
        d.setGroups(lookups(foreign));

        refused(() -> dao.insertDevice(d));

        assertEquals(0L, scalar("SELECT COUNT(*) FROM " + schema + ".devices WHERE number = '" + number + "'"));
    }

    @Test
    public void aNewDeviceIsAddedIntoTheUsersOwnGroup() throws Exception {
        SecurityContext.init(limited);
        Device d = new Device();
        String number = "new-" + UUID.randomUUID().toString().substring(0, 8);
        d.setNumber(number);
        d.setConfigurationId(configuration);
        d.setLastUpdate(0L);
        d.setGroups(lookups(open));

        dao.insertDevice(d);

        int id = (int) scalar("SELECT id FROM " + schema + ".devices WHERE number = '" + number + "'");
        assertEquals(Collections.singletonList(open), groupsOf(id));
    }

    @Test
    public void groupsAreCheckedAgainstTheCustomerAndTheUsersAccess() {
        SecurityContext.init(admin);
        assertTrue(dao.areGroupsUsableByUser(Arrays.asList(open, closed)));
        assertTrue(dao.areGroupsUsableByUser(Collections.emptyList()));
        assertFalse(dao.areGroupsUsableByUser(Arrays.asList(open, foreign)));
        assertFalse(dao.areGroupsUsableByUser(Collections.singletonList(null)));
        SecurityContext.release();

        SecurityContext.init(limited);
        assertTrue(dao.areGroupsUsableByUser(Arrays.asList(open, open)));
        assertFalse(dao.areGroupsUsableByUser(Collections.singletonList(closed)));
    }

    @Test
    public void aBulkRegroupIntoAnotherCustomersGroupChangesNoDevice() throws Exception {
        int a = device(open);
        int b = device();
        SecurityContext.init(admin);

        refused(() -> dao.updateDevicesGroups(Arrays.asList(a, b), lookups(closed, foreign), true));

        assertEquals(Collections.singletonList(open), groupsOf(a));
        assertEquals(Collections.emptyList(), groupsOf(b));
    }

    @Test
    public void aBulkRegroupIntoAGroupTheUserMayNotUseChangesNoDevice() throws Exception {
        int a = device(open);
        int b = device(open);
        SecurityContext.init(limited);

        refused(() -> dao.updateDevicesGroups(Arrays.asList(a, b), lookups(closed), true));

        assertEquals(Collections.singletonList(open), groupsOf(a));
        assertEquals(Collections.singletonList(open), groupsOf(b));
    }

    @Test
    public void aBulkRemovalFromAGroupTheUserMayNotUseChangesNoDevice() throws Exception {
        int a = device(open, closed);
        int b = device(open, closed);
        SecurityContext.init(limited);

        refused(() -> dao.updateDevicesGroups(Arrays.asList(a, b), lookups(open, closed), false));

        assertEquals(Arrays.asList(open, closed), groupsOf(a));
        assertEquals(Arrays.asList(open, closed), groupsOf(b));
    }

    @Test
    public void aBulkRegroupWithAGroupThatDoesNotExistChangesNoDevice() throws Exception {
        int a = device(open);
        SecurityContext.init(admin);

        refused(() -> dao.updateDevicesGroups(Collections.singletonList(a), lookups(closed, 2_000_000_000), true));

        assertEquals(Collections.singletonList(open), groupsOf(a));
    }

    @Test
    public void aBulkRegroupWithinTheUsersOwnGroupsChangesEveryDevice() throws Exception {
        int a = device(open);
        int b = device();
        SecurityContext.init(admin);

        dao.updateDevicesGroups(Arrays.asList(a, b), lookups(open, closed, closed), true);
        assertEquals(Arrays.asList(open, closed), groupsOf(a));
        assertEquals(Arrays.asList(open, closed), groupsOf(b));

        dao.updateDevicesGroups(Arrays.asList(a, b), lookups(closed), false);
        assertEquals(Collections.singletonList(open), groupsOf(a));
        assertEquals(Collections.singletonList(open), groupsOf(b));
    }

    @Test
    public void anAccessRowToAnotherCustomersGroupGrantsNothing() throws Exception {
        // Only groups of the user's own customer count, whatever userDeviceGroupsAccess says
        User granted = user(false);
        sql("INSERT INTO " + schema + ".userDeviceGroupsAccess (userId, groupId) VALUES (" + granted.getId() + ", "
                + open + "), (" + granted.getId() + ", " + foreign + ")");
        int device = device(open);
        SecurityContext.init(granted);

        assertFalse(dao.areGroupsUsableByUser(Collections.singletonList(foreign)));
        refused(() -> dao.updateDevicesGroups(Collections.singletonList(device), lookups(foreign), true));
        Device change = edit(device, open, foreign);
        refused(() -> dao.updateDevice(change));

        assertEquals(Collections.singletonList(open), groupsOf(device));
    }

    @Test
    public void anEditChecksTheGroupsAsTheyAreOnceItHoldsTheDevice() throws Exception {
        // Another transaction holds the device and takes it out of `closed`. An edit sent meanwhile, keeping `closed`
        // (which the limited user may keep but not add), must wait and check against the groups that transaction
        // leaves: read before the lock, `closed` would look already there and be put back.
        int device = device(open, closed);
        Device change = edit(device, open, closed);
        String url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection other = DriverManager.getConnection(url, user(), password()); Statement s = other.createStatement()) {
            other.setAutoCommit(false);
            long holder;
            try (ResultSet r = s.executeQuery("SELECT pg_backend_pid()")) {
                r.next();
                holder = r.getLong(1);
            }
            s.execute("SELECT id FROM devices WHERE id = " + device + " FOR UPDATE");
            s.execute("DELETE FROM deviceGroups WHERE deviceId = " + device + " AND groupId = " + closed);

            CompletableFuture<Throwable> edit = CompletableFuture.supplyAsync(() -> {
                SecurityContext.init(limited);
                try {
                    dao.updateDevice(change);
                    return null;
                } catch (Throwable t) {
                    return t;
                } finally {
                    SecurityContext.release();
                }
            });
            long deadline = System.currentTimeMillis() + 10_000;
            // Only the edit can be waiting on this transaction: it holds nothing anyone else wants
            while (scalar("SELECT count(*) FROM pg_stat_activity WHERE " + holder
                    + " = ANY(pg_blocking_pids(pid))") == 0) {
                assertFalse("the edit never waited for the device's lock", edit.isDone());
                assertTrue("the edit never waited for the device's lock", System.currentTimeMillis() < deadline);
                Thread.sleep(20);
            }
            other.commit();

            Throwable outcome = edit.get(10, TimeUnit.SECONDS);
            assertTrue("expected the edit to be refused, got " + outcome, outcome instanceof SecurityException);
        }
        assertEquals(Collections.singletonList(open), groupsOf(device));
        assertEquals("unchanged", descriptionOf(device));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static void refused(Runnable change) {
        try {
            change.run();
            fail("expected the change to be refused");
        } catch (SecurityException expected) {
            // refused
        }
    }

    /** A device of {@link #customer} in these groups, described "unchanged". */
    private static int device(int... groups) throws Exception {
        String number = "d-" + UUID.randomUUID().toString().substring(0, 8);
        int id = (int) scalar("INSERT INTO " + schema + ".devices (number, description, customerId, configurationId, "
                + "lastUpdate) VALUES ('" + number + "', 'unchanged', " + customer + ", " + configuration
                + ", 0) RETURNING id");
        for (int g : groups) {
            sql("INSERT INTO " + schema + ".deviceGroups (deviceId, groupId) VALUES (" + id + ", " + g + ")");
        }
        return id;
    }

    /** The console's edit of a device: its stored fields, described "edited", in these groups. */
    private static Device edit(int deviceId, int... groups) throws Exception {
        Device d = new Device();
        d.setId(deviceId);
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT number FROM " + schema + ".devices WHERE id = " + deviceId)) {
            r.next();
            d.setNumber(r.getString(1));
        }
        d.setDescription("edited");
        d.setConfigurationId(configuration);
        d.setGroups(lookups(groups));
        return d;
    }

    private static List<LookupItem> lookups(int... ids) {
        List<LookupItem> items = new ArrayList<>();
        for (int id : ids) {
            items.add(new LookupItem(id, null));
        }
        return items;
    }

    private static List<Integer> groupsOf(int deviceId) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT groupId FROM " + schema + ".deviceGroups WHERE deviceId = "
                     + deviceId + " ORDER BY groupId")) {
            while (r.next()) {
                ids.add(r.getInt(1));
            }
        }
        return ids;
    }

    private static String descriptionOf(int deviceId) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT description FROM " + schema + ".devices WHERE id = " + deviceId)) {
            r.next();
            return r.getString(1);
        }
    }

    /** A servlet context that only answers init parameters, as the server's context.xml provides them. */
    private static ServletContext servletContext(Map<String, String> params) {
        return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getInitParameterNames": return Collections.enumeration(params.keySet());
                        case "getInitParameter": return params.get((String) args[0]);
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static String user() {
        return USER == null ? "postgres" : USER;
    }

    private static String password() {
        return PASSWORD == null ? "" : PASSWORD;
    }

    private static void sql(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement()) {
            for (String st : statements) s.execute(st);
        }
    }

    private static long scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
        }
    }
}
