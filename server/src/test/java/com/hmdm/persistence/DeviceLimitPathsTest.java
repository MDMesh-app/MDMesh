/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.DeviceCreateOptions;
import com.hmdm.persistence.domain.User;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.CryptoUtil;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The device limit across the REAL device-adding paths, on the real schema (the server's Liquibase changelog) and the
 * real DAOs built by Guice + mybatis-guice as in production: {@link DeviceDAO#insertDeviceWithinLimit} (the console),
 * {@link UnsecureDAO#createNewDeviceOnDemand(String, DeviceCreateOptions)} and enrollment, raced both ways round.
 * Removing {@code @Transactional} from either path, or its lock, lets the race overfill the customer and fails here.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest for the docker one-liner); with
 * MDMESH_TEST_PG_REQUIRED it fails without one. Works in its own schema, dropped afterwards.</p>
 */
public class DeviceLimitPathsTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    /** Holds an enrollment inside its transaction, after its limit count and its device insert. */
    static final class Hold {
        static volatile CountDownLatch inside;
        static volatile CountDownLatch proceed;
    }

    /** The production device factory (UnsecureDAO.createNewDeviceForToken), able to stop inside the transaction. */
    public static final class HoldingFactory implements AgentEnrollmentDAO.DeviceFactory {
        private final UnsecureDAO unsecureDAO;

        @Inject
        public HoldingFactory(UnsecureDAO unsecureDAO) {
            this.unsecureDAO = unsecureDAO;
        }

        @Override
        public Device create(String number, int customerId, Integer configurationId) {
            Device d = unsecureDAO.createNewDeviceForToken(number, customerId, configurationId);
            CountDownLatch inside = Hold.inside;
            if (inside != null) {
                inside.countDown();
                try {
                    Hold.proceed.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return d;
        }
    }

    private static String schema;
    private static Injector injector;

    private ExecutorService pool;
    private int customerId;
    private String customerName;
    private int configurationId;
    private int otherConfigurationId;
    private String qrKey;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "limit_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema);
        String url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection c = DriverManager.getConnection(url, user(), password())) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("liquibase/db.changelog.xml", new ClassLoaderResourceAccessor(), db).update("common,private");
        }

        // The server's own persistence module (PersistenceModule → AbstractPersistenceModule: pooled data source,
        // JDBC transactions, every mapper, the domain aliases, the JSON type handler), fed the JDBC settings the way the
        // servlet context feeds it. Only the named settings its DAOs' collaborators take, and the device factory seam,
        // are added here. The pool's connections carry an application name so the wait probe below sees only them.
        Map<String, String> jdbc = new HashMap<>();
        jdbc.put("JDBC.driver", "org.postgresql.Driver");
        jdbc.put("JDBC.url", url + "&ApplicationName=" + schema);
        jdbc.put("JDBC.username", user());
        jdbc.put("JDBC.password", password());
        injector = Guice.createInjector(new PersistenceModule(servletContext(jdbc)), new AbstractModule() {
            @Override
            protected void configure() {
                Properties props = new Properties();
                props.setProperty("files.directory", "/nonexistent-mdmesh-test-files");
                props.setProperty("role.orgadmin.id", "2");
                props.setProperty("launcher.package", "com.hmdm.launcher");
                props.setProperty("device.fast.search.chars", "5");
                // Settings the DAOs' collaborators take; unused by these paths.
                props.setProperty("aapt.command", "aapt");
                props.setProperty("apk.trusted.url", "");
                props.setProperty("base.url", "https://mdm.example.test");
                props.setProperty("hash.secret", "test-hash-secret");
                Names.bindProperties(binder(), props);
                bind(AgentEnrollmentDAO.DeviceFactory.class).to(HoldingFactory.class);
            }
        });
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

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (schema != null) sql("DROP SCHEMA " + schema + " CASCADE");
    }

    @Before
    public void setUp() throws Exception {
        pool = Executors.newFixedThreadPool(4);
        Hold.inside = null;
        Hold.proceed = null;
        // A second customer (the install is then multi-tenant) with room for exactly one more device: 2 of 3.
        customerName = "c" + UUID.randomUUID().toString().substring(0, 8);
        customerId = (int) scalar("INSERT INTO " + schema + ".customers (name, filesDir, prefix, deviceLimit) VALUES ('"
                + customerName + "', '" + customerName + "', '" + customerName + "', 3) RETURNING id");
        qrKey = UUID.randomUUID().toString().replace("-", "");
        configurationId = (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId, qrCodeKey) VALUES ('"
                + customerName + "-a', " + customerId + ", '" + qrKey + "') RETURNING id");
        otherConfigurationId = (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId) VALUES ('"
                + customerName + "-b', " + customerId + ") RETURNING id");
        for (int i = 0; i < 2; i++) {
            sql("INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate) VALUES ('"
                    + customerName + "-existing-" + i + "', " + customerId + ", " + configurationId + ", 0)");
        }
    }

    @After
    public void tearDown() {
        if (pool != null) pool.shutdownNow();
        if (Hold.proceed != null) Hold.proceed.countDown();
    }

    private static String user() {
        return USER == null ? "postgres" : USER;
    }

    private static String password() {
        return PASSWORD == null ? "" : PASSWORD;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(URL, user(), password());
    }

    private static void sql(String... statements) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String st : statements) s.execute(st);
        }
    }

    private static long scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
        }
    }

    private long devices() throws Exception {
        return scalar("SELECT count(*) FROM " + schema + ".devices WHERE customerId = " + customerId);
    }

    private int token() throws Exception {
        return (int) scalar("INSERT INTO " + schema + ".agentEnrollmentToken (token, customerId, used, createdAt, "
                + "expiresAt, configurationId, maxUses) VALUES ('" + UUID.randomUUID() + "', " + customerId + ", false, 0, "
                + (System.currentTimeMillis() + 3_600_000L) + ", " + otherConfigurationId + ", 5) RETURNING id");
    }

    /** An enroll with a code of the customer: "CREATED" or "LIMIT". */
    private Callable<String> enroll(int tokenId) {
        AgentEnrollmentDAO enrollments = injector.getInstance(AgentEnrollmentDAO.class);
        return () -> {
            try {
                return enrollments.enroll(tokenId, customerId, otherConfigurationId, UUID.randomUUID().toString(),
                        "{}", null, null, false).kind.name();
            } catch (AgentEnrollmentDAO.DeviceLimitException e) {
                return "LIMIT";
            }
        };
    }

    /** A device added in the console (DeviceResource → DeviceDAO.insertDeviceWithinLimit), as the customer's admin. */
    private Callable<Boolean> consoleAdd(String number) {
        DeviceDAO deviceDAO = injector.getInstance(DeviceDAO.class);
        User admin = Fakes.user(9, customerId, "edit_devices", "devices");
        admin.setAllDevicesAvailable(true);
        admin.setAllConfigAvailable(true);
        return () -> {
            SecurityContext.init(admin);
            try {
                Device d = new Device();
                d.setNumber(number);
                d.setConfigurationId(configurationId);
                d.setLastUpdate(0L);
                return deviceDAO.insertDeviceWithinLimit(d, 3);
            } finally {
                SecurityContext.release();
            }
        };
    }

    /** A device created on demand (the legacy launcher's sync with a customer and a configuration QR key). */
    private Callable<Boolean> onDemand(String number) {
        UnsecureDAO unsecureDAO = injector.getInstance(UnsecureDAO.class);
        return () -> {
            DeviceCreateOptions options = new DeviceCreateOptions();
            options.setCustomer(customerName);
            options.setConfiguration(qrKey);
            return unsecureDAO.createNewDeviceOnDemand(number, options) != null;
        };
    }

    /** Starts an enroll that stops inside its transaction, after counting and inserting. */
    private Future<String> enrollHeldOpen() throws Exception {
        Hold.inside = new CountDownLatch(1);
        Hold.proceed = new CountDownLatch(1);
        Future<String> f = pool.submit(enroll(token()));
        assertTrue("the enroll is inside its transaction", Hold.inside.await(10, TimeUnit.SECONDS));
        Hold.inside = null;
        return f;
    }

    private static <T> void assertStillWaiting(Future<T> f, String what) throws Exception {
        try {
            T early = f.get(1500, TimeUnit.MILLISECONDS);
            fail(what + " finished (" + early + ") while the other add held the customer's count");
        } catch (TimeoutException expected) {
            // waiting on the customer's lock
        }
    }

    /** Runs {@code other} while the enroll is held open: it must wait, then find the customer full. */
    private void raceAgainstHeldEnroll(Callable<Boolean> other, String what) throws Exception {
        Future<String> enroll = enrollHeldOpen();
        Future<Boolean> add = pool.submit(other);
        try {
            assertStillWaiting(add, what);
        } finally {
            Hold.proceed.countDown();
        }
        assertEquals("CREATED", enroll.get(20, TimeUnit.SECONDS));
        assertFalse(what + " finds the customer full", add.get(20, TimeUnit.SECONDS));
        assertEquals(3, devices());
    }

    /**
     * Runs {@code first} (adding device {@code number}) so that it stops inside its transaction, after its limit
     * count, on its device insert: a raw transaction has inserted the same number, uncommitted, so the insert waits on
     * the unique index. An enroll meanwhile must wait, then find the customer full. Without the transaction the
     * count's lock is gone before the insert, and the enroll gets in.
     */
    private void raceEnrollAgainstHeld(Callable<Boolean> first, String number, String what) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate) VALUES ('"
                    + number + "', " + customerId + ", " + configurationId + ", 0)");
            Future<Boolean> add = pool.submit(first);
            // Wait until it is blocked on its insert (it has counted under the lock by then).
            long until = System.currentTimeMillis() + 10_000;
            while (scalar("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND application_name = '" + schema + "' AND wait_event_type = 'Lock' "
                    + "AND query ILIKE '%INSERT INTO devices%'") == 0) {
                if (System.currentTimeMillis() > until) fail(what + " never reached its device insert");
                Thread.sleep(50);
            }
            Future<String> enroll = pool.submit(enroll(token()));
            try {
                assertStillWaiting(enroll, "the enroll");
            } finally {
                c.rollback(); // the number is free again: the held add's insert goes through
            }
            assertTrue(what + " adds its device", add.get(20, TimeUnit.SECONDS));
            assertEquals("the enroll finds the customer full", "LIMIT", enroll.get(20, TimeUnit.SECONDS));
        }
        assertEquals(3, devices());
    }

    @Test
    public void a_console_add_during_an_enroll_waits_and_finds_the_customer_full() throws Exception {
        raceAgainstHeldEnroll(consoleAdd(customerName + "-console"), "the console add");
    }

    @Test
    public void an_enroll_during_a_console_add_waits_and_finds_the_customer_full() throws Exception {
        raceEnrollAgainstHeld(consoleAdd(customerName + "-console"), customerName + "-console", "the console add");
    }

    @Test
    public void an_on_demand_device_during_an_enroll_waits_and_finds_the_customer_full() throws Exception {
        raceAgainstHeldEnroll(onDemand(customerName + "-ondemand"), "on-demand creation");
    }

    @Test
    public void an_enroll_during_on_demand_creation_waits_and_finds_the_customer_full() throws Exception {
        raceEnrollAgainstHeld(onDemand(customerName + "-ondemand"), customerName + "-ondemand", "on-demand creation");
    }

    @Test
    public void the_paths_refuse_a_full_customer_on_their_own() throws Exception {
        assertTrue(pool.submit(consoleAdd(customerName + "-third")).get(20, TimeUnit.SECONDS));
        assertFalse(pool.submit(consoleAdd(customerName + "-fourth")).get(20, TimeUnit.SECONDS));
        assertFalse(pool.submit(onDemand(customerName + "-fifth")).get(20, TimeUnit.SECONDS));
        assertEquals("LIMIT", pool.submit(enroll(token())).get(20, TimeUnit.SECONDS));
        assertEquals(3, devices());
        assertNull(injector.getInstance(UnsecureDAO.class).getDeviceByNumber(customerName + "-fifth"));
        assertNotNull(injector.getInstance(UnsecureDAO.class).getDeviceByNumber(customerName + "-third"));
    }
}
