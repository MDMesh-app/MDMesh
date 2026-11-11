/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import com.hmdm.util.CryptoUtil;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.guice.MyBatisModule;
import org.mybatis.guice.datasource.builtin.PooledDataSourceProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link AgentEnrollmentDAO} against a real PostgreSQL, through Guice and mybatis-guice exactly as the server builds it,
 * so {@code @Transactional} really spans the token lock, the claim, the device insert and the completion.
 *
 * <p>Needs a database it may create a schema in; skipped without one. Run it with a throwaway container:</p>
 * <pre>
 * docker run -d --rm --name mdmesh-enroll-it -e POSTGRES_PASSWORD=x -p 127.0.0.1:55432:5432 postgres:14
 * MDMESH_TEST_PG_URL=jdbc:postgresql://127.0.0.1:55432/postgres MDMESH_TEST_PG_USER=postgres \
 *   MDMESH_TEST_PG_PASSWORD=x mvn -pl server -am test -Dtest=AgentEnrollmentTransactionTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false
 * docker stop mdmesh-enroll-it
 * </pre>
 * <p>Each run works in its own schema (dropped afterwards), with the columns these statements use. CI (t0-fast.yml, the
 * server entry) runs it against a postgres service and sets MDMESH_TEST_PG_REQUIRED, so there it fails rather than
 * skips without a database.</p>
 */
public class AgentEnrollmentTransactionTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    /** Inserts the device row (what UnsecureDAO.createNewDeviceForToken does, minus settings). */
    public interface TestDeviceMapper {
        @Insert({"INSERT INTO devices (number, customerId, configurationId, lastUpdate) " +
                "VALUES (#{number}, #{customerId}, #{configurationId}, 0)"})
        void insert(@Param("number") String number, @Param("customerId") int customerId,
                    @Param("configurationId") Integer configurationId);

        /** The lock DeviceMapper.lockCustomerDevices takes for console and on-demand device creation. */
        @org.apache.ibatis.annotations.Select({CustomerDeviceLimit.LOCK_SQL})
        Integer lockCustomerDevices(@Param("customerId") int customerId);

        @org.apache.ibatis.annotations.Select({"SELECT COUNT(*) FROM devices WHERE customerId = #{customerId}"})
        long count(@Param("customerId") int customerId);
    }

    /**
     * A device added the way the console (DeviceDAO.insertDeviceWithinLimit) and on-demand creation
     * (UnsecureDAO.createNewDeviceOnDemand) add one: the shared rule, then the insert, in one transaction.
     */
    public static class ConsoleAdd {
        private final TestDeviceMapper mapper;

        @Inject
        public ConsoleAdd(TestDeviceMapper mapper) {
            this.mapper = mapper;
        }

        @org.mybatis.guice.transactional.Transactional
        public boolean add(String number, int customerId, int limit) {
            if (!CustomerDeviceLimit.roomForOne(limit, () -> mapper.lockCustomerDevices(customerId),
                    () -> mapper.count(customerId))) {
                return false;
            }
            mapper.insert(number, customerId, null);
            return true;
        }
    }

    /** Hooks into device creation, inside the enrollment transaction. */
    static final class Hooks {
        static volatile CountDownLatch inside;
        static volatile CountDownLatch proceed;
        static volatile RuntimeException failAfterInsert;
        static volatile boolean refuse;

        static void reset() {
            inside = null;
            proceed = null;
            failAfterInsert = null;
            refuse = false;
        }
    }

    public static final class TestDeviceFactory implements AgentEnrollmentDAO.DeviceFactory {
        private final TestDeviceMapper mapper;

        @Inject
        public TestDeviceFactory(TestDeviceMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Device create(String number, int customerId, Integer configurationId) {
            if (Hooks.refuse) return null;
            mapper.insert(number, customerId, configurationId);
            CountDownLatch inside = Hooks.inside;
            if (inside != null) {
                inside.countDown();
                try {
                    Hooks.proceed.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (Hooks.failAfterInsert != null) throw Hooks.failAfterInsert;
            Device d = new Device();
            d.setNumber(number);
            d.setCustomerId(customerId);
            d.setConfigurationId(configurationId);
            return d;
        }
    }

    private String schema;
    private AgentEnrollmentDAO enrollments;
    private AgentDeviceMapper deviceMapper;
    private ConsoleAdd consoleAdd;
    private ExecutorService pool;

    @Before
    public void setUp() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        Hooks.reset();
        schema = "enroll_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema,
                "CREATE TABLE " + schema + ".agentEnrollmentToken (id serial PRIMARY KEY, token VARCHAR(64), "
                        + "customerId INT NOT NULL, used BOOLEAN NOT NULL DEFAULT false, createdAt BIGINT, "
                        + "expiresAt BIGINT, configurationId INT, maxUses INT NOT NULL DEFAULT 1, "
                        + "useCount INT NOT NULL DEFAULT 0, revokedAt BIGINT)",
                "CREATE TABLE " + schema + ".customers (id INT PRIMARY KEY, name VARCHAR(50), "
                        + "deviceLimit INT NOT NULL DEFAULT 3, master BOOLEAN NOT NULL DEFAULT false, "
                        + "lastLoginTime BIGINT)",
                "INSERT INTO " + schema + ".customers (id, name, deviceLimit) VALUES (1, 'default', 3)",
                "CREATE TABLE " + schema + ".devices (id serial PRIMARY KEY, number VARCHAR(100) UNIQUE NOT NULL, "
                        + "customerId INT REFERENCES " + schema + ".customers(id), configurationId INT, "
                        + "lastUpdate BIGINT, enrollTime BIGINT, description TEXT, "
                        + "agentSecretHash VARCHAR(64), agentCapabilities TEXT, hardwareid VARCHAR(100), "
                        + "agentEnrollAttemptHash VARCHAR(64))",
                "CREATE TABLE " + schema + ".users (id INT PRIMARY KEY, customerId INT, "
                        + "allDevicesAvailable BOOLEAN NOT NULL DEFAULT true)",
                "CREATE TABLE " + schema + ".deviceGroups (id serial PRIMARY KEY, deviceId INT NOT NULL, groupId INT)",
                "CREATE TABLE " + schema + ".userDeviceGroupsAccess (id serial PRIMARY KEY, userId INT, groupId INT)");

        Properties props = new Properties();
        props.setProperty("JDBC.driver", "org.postgresql.Driver");
        props.setProperty("JDBC.url", URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        props.setProperty("JDBC.username", USER == null ? "postgres" : USER);
        props.setProperty("JDBC.password", PASSWORD == null ? "" : PASSWORD);
        props.setProperty("mybatis.pooled.maximumActiveConnections", "10");
        Injector injector = Guice.createInjector(new MyBatisModule() {
            @Override
            protected void initialize() {
                environmentId("test");
                Names.bindProperties(binder(), props);
                bindDataSourceProviderType(PooledDataSourceProvider.class);
                bindTransactionFactoryType(JdbcTransactionFactory.class);
                addMapperClass(AgentEnrollmentTokenMapper.class);
                addMapperClass(AgentDeviceMapper.class);
                addMapperClass(TestDeviceMapper.class);
                bind(ConsoleAdd.class);
                bind(AgentEnrollmentDAO.DeviceFactory.class).to(TestDeviceFactory.class);
            }
        });
        enrollments = injector.getInstance(AgentEnrollmentDAO.class);
        deviceMapper = injector.getInstance(AgentDeviceMapper.class);
        consoleAdd = injector.getInstance(ConsoleAdd.class);
        pool = Executors.newFixedThreadPool(8);
    }

    @After
    public void tearDown() throws Exception {
        if (pool != null) pool.shutdownNow();
        Hooks.reset();
        if (schema != null && URL != null) sql("DROP SCHEMA " + schema + " CASCADE");
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(URL, USER == null ? "postgres" : USER, PASSWORD == null ? "" : PASSWORD);
    }

    private void sql(String... statements) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String st : statements) s.execute(st);
        }
    }

    private long count(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** A second customer (the install is then multi-tenant) with this device limit; returns its id. */
    private int customer(int deviceLimit) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "INSERT INTO " + schema + ".customers (id, name, deviceLimit) SELECT max(id) + 1, 'c', "
                        + deviceLimit + " FROM " + schema + ".customers RETURNING id")) {
            r.next();
            return r.getInt(1);
        }
    }

    private void existingDevices(int customerId, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            sql("INSERT INTO " + schema + ".devices (number, customerId, agentSecretHash) VALUES ('existing-"
                    + customerId + "-" + i + "', " + customerId + ", 'S')");
        }
    }

    private long devicesOf(int customerId) throws Exception {
        return count("SELECT count(*) FROM " + schema + ".devices WHERE customerId = " + customerId);
    }

    private int token(int maxUses) throws Exception {
        return token(maxUses, 1);
    }

    private int token(int maxUses, int customerId) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "INSERT INTO " + schema + ".agentEnrollmentToken (token, customerId, createdAt, expiresAt, maxUses) "
                        + "VALUES ('h', " + customerId + ", 0, " + (System.currentTimeMillis() + 3_600_000L) + ", " + maxUses
                        + ") RETURNING id")) {
            r.next();
            return r.getInt(1);
        }
    }

    private long devices() throws Exception {
        return count("SELECT count(*) FROM " + schema + ".devices");
    }

    private long uses(int tokenId) throws Exception {
        return count("SELECT useCount FROM " + schema + ".agentEnrollmentToken WHERE id = " + tokenId);
    }

    private AgentEnrollmentDAO.Outcome enroll(int tokenId, String attempt) {
        return enroll(tokenId, 1, attempt);
    }

    private AgentEnrollmentDAO.Outcome enroll(int tokenId, int customerId, String attempt) {
        String attemptHash = attempt == null ? null : CryptoUtil.getSHA256String(tokenId + ":" + attempt);
        return enrollments.enroll(tokenId, customerId, null, UUID.randomUUID().toString(),
                CryptoUtil.getSHA256String(UUID.randomUUID().toString()), "{}", "hw", attemptHash);
    }

    /** A retry sent while the first request is still inside its transaction: it waits, then resumes that device. */
    private void concurrentRetryOfOneAttempt(int maxUses) throws Exception {
        int tokenId = token(maxUses);
        Hooks.inside = new CountDownLatch(1);
        Hooks.proceed = new CountDownLatch(1);
        Future<AgentEnrollmentDAO.Outcome> first = pool.submit(() -> enroll(tokenId, "attempt-0123456789abcdef"));
        assertTrue("the first request is inside its transaction", Hooks.inside.await(10, TimeUnit.SECONDS));
        Hooks.inside = null; // the retry creates nothing, but must not wait here if it ever did

        Future<AgentEnrollmentDAO.Outcome> retry = pool.submit(() -> enroll(tokenId, "attempt-0123456789abcdef"));
        try {
            retry.get(700, TimeUnit.MILLISECONDS);
            fail("the retry must wait for the first request's transaction");
        } catch (TimeoutException expected) {
            // blocked on the token row lock
        }
        Hooks.proceed.countDown();

        AgentEnrollmentDAO.Outcome a = first.get(20, TimeUnit.SECONDS);
        AgentEnrollmentDAO.Outcome b = retry.get(20, TimeUnit.SECONDS);
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, a.kind);
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.RESUMED, b.kind);
        assertEquals("the same device", a.deviceNumber, b.deviceNumber);
        assertEquals("one device", 1, devices());
        assertEquals("one use", 1, uses(tokenId));
    }

    @Test
    public void a_retry_during_the_first_request_resumes_it_on_a_single_use_code() throws Exception {
        concurrentRetryOfOneAttempt(1);
    }

    @Test
    public void a_retry_during_the_first_request_resumes_it_on_a_batch_code() throws Exception {
        concurrentRetryOfOneAttempt(5);
    }

    @Test
    public void a_batch_code_enrolls_exactly_its_devices_when_more_enroll_at_once() throws Exception {
        int tokenId = token(2);
        List<Future<AgentEnrollmentDAO.Outcome>> all = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 6; i++) {
            String attempt = "attempt-" + i + "-0123456789abcdef";
            all.add(pool.submit(() -> {
                start.await();
                return enroll(tokenId, attempt);
            }));
        }
        start.countDown();
        int created = 0;
        int refused = 0;
        for (Future<AgentEnrollmentDAO.Outcome> f : all) {
            AgentEnrollmentDAO.Outcome o = f.get(30, TimeUnit.SECONDS);
            if (o.kind == AgentEnrollmentDAO.Outcome.Kind.CREATED) created++;
            if (o.kind == AgentEnrollmentDAO.Outcome.Kind.NO_USE) refused++;
        }
        assertEquals(2, created);
        assertEquals(4, refused);
        assertEquals(2, devices());
        assertEquals(2, uses(tokenId));
        assertEquals(1, count("SELECT count(*) FROM " + schema + ".agentEnrollmentToken WHERE used"));
    }

    @Test
    public void a_failure_after_the_device_was_inserted_keeps_nothing_and_spends_no_use() throws Exception {
        int tokenId = token(1);
        Hooks.failAfterInsert = new IllegalStateException("connection lost");
        try {
            enroll(tokenId, "attempt-0123456789abcdef");
            fail("the failure reaches the caller");
        } catch (RuntimeException expected) {
            // rolled back
        }
        assertEquals("no half device", 0, devices());
        assertEquals("the use is not spent", 0, uses(tokenId));

        Hooks.failAfterInsert = null;
        AgentEnrollmentDAO.Outcome retry = enroll(tokenId, "attempt-0123456789abcdef");
        assertEquals("the retry starts fresh and works", AgentEnrollmentDAO.Outcome.Kind.CREATED, retry.kind);
        assertEquals(1, devices());
        assertEquals(1, count("SELECT count(*) FROM " + schema + ".devices WHERE agentSecretHash IS NOT NULL"));
    }

    @Test
    public void a_refused_creation_spends_no_use() throws Exception {
        int tokenId = token(1);
        Hooks.refuse = true;
        try {
            enroll(tokenId, null);
            fail("expected CreationRefusedException");
        } catch (AgentEnrollmentDAO.CreationRefusedException expected) {
            // rolled back
        }
        assertEquals(0, uses(tokenId));
        Hooks.refuse = false;
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, enroll(tokenId, null).kind);
    }

    @Test
    public void the_first_check_in_closes_the_retry_and_a_used_code_stays_used() throws Exception {
        int tokenId = token(1);
        AgentEnrollmentDAO.Outcome first = enroll(tokenId, "attempt-0123456789abcdef");
        assertNotNull(first.deviceNumber);
        sql("UPDATE " + schema + ".devices SET lastUpdate = 1, agentEnrollAttemptHash = NULL WHERE number = '"
                + first.deviceNumber + "'");

        assertEquals(AgentEnrollmentDAO.Outcome.Kind.NO_USE, enroll(tokenId, "attempt-0123456789abcdef").kind);
        assertFalse(count("SELECT count(*) FROM " + schema + ".devices") > 1);
    }

    @Test
    public void a_customer_at_its_device_limit_enrolls_no_more_and_the_code_keeps_its_use() throws Exception {
        int customerId = customer(2);
        existingDevices(customerId, 2);
        int tokenId = token(5, customerId);
        try {
            enroll(tokenId, customerId, "attempt-0123456789abcdef");
            fail("expected DeviceLimitException");
        } catch (AgentEnrollmentDAO.DeviceLimitException expected) {
            assertEquals(2, expected.limit);
        }
        assertEquals(2, devicesOf(customerId));
        assertEquals("the use is not spent", 0, uses(tokenId));
    }

    @Test
    public void enrolls_from_different_codes_at_once_never_pass_the_device_limit_together() throws Exception {
        // Room for exactly one. The first enroll stops inside its transaction after counting (2 < 3) and inserting;
        // the second, on another code (so no token lock in common), must wait for the customer's lock instead of
        // counting 2 as well and adding a fourth device.
        int customerId = customer(3);
        existingDevices(customerId, 2);
        int tokenA = token(5, customerId);
        int tokenB = token(5, customerId);
        Future<AgentEnrollmentDAO.Outcome> first = enrollHeldOpen(tokenA, customerId);
        Hooks.inside = null; // the second must not stop in the factory: without the lock it would create at once
        Future<String> second = pool.submit(() -> {
            try {
                return enroll(tokenB, customerId, "attempt-second-0123456789").kind.name();
            } catch (AgentEnrollmentDAO.DeviceLimitException e) {
                return "LIMIT";
            }
        });
        try {
            String early = second.get(1500, TimeUnit.MILLISECONDS);
            fail("the second enroll finished (" + early + ") while the first held the customer's count: devices "
                    + devicesOf(customerId));
        } catch (TimeoutException expected) {
            // waiting on the customer's lock
        } finally {
            Hooks.proceed.countDown();
        }
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, first.get(20, TimeUnit.SECONDS).kind);
        assertEquals("the second finds the customer full", "LIMIT", second.get(20, TimeUnit.SECONDS));
        assertEquals(3, devicesOf(customerId));
        assertEquals("one use; the refused enroll's use rolled back", 1, uses(tokenA) + uses(tokenB));
    }

    @Test
    public void a_device_added_in_the_console_during_an_enroll_does_not_pass_the_limit_with_it() throws Exception {
        // Room for one: the enroll counts 2 < 3 and stops before committing; the console add must wait for it, then
        // find the customer full, instead of counting 2 as well.
        int customerId = customer(3);
        existingDevices(customerId, 2);
        Future<AgentEnrollmentDAO.Outcome> enroll = enrollHeldOpen(token(5, customerId), customerId);
        Future<Boolean> console = pool.submit(() -> consoleAdd.add("console-added", customerId, 3));
        try {
            Boolean early = console.get(1500, TimeUnit.MILLISECONDS);
            fail("the console add finished (" + early + ") while the enroll held the customer's count");
        } catch (TimeoutException expected) {
            // waiting on the customer's lock
        } finally {
            Hooks.proceed.countDown();
        }
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, enroll.get(20, TimeUnit.SECONDS).kind);
        assertFalse("the console add finds the customer full", console.get(20, TimeUnit.SECONDS));
        assertEquals(3, devicesOf(customerId));
    }

    @Test
    public void an_enroll_during_a_console_add_does_not_pass_the_limit_with_it() throws Exception {
        int customerId = customer(3);
        existingDevices(customerId, 2);
        int tokenId = token(5, customerId);
        // The console add holds the customer's lock (its transaction, opened on a raw connection) while the enroll runs.
        try (Connection c = connect(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SET search_path = " + schema);
            st.executeQuery("SELECT pg_advisory_xact_lock(1296321861, " + customerId + ")").close();
            st.execute("INSERT INTO devices (number, customerId) VALUES ('console-first', " + customerId + ")");
            Future<String> enroll = pool.submit(() -> {
                try {
                    return enroll(tokenId, customerId, "attempt-after-console-0123").kind.name();
                } catch (AgentEnrollmentDAO.DeviceLimitException e) {
                    return "LIMIT";
                }
            });
            try {
                String early = enroll.get(1500, TimeUnit.MILLISECONDS);
                fail("the enroll finished (" + early + ") while the console add held the customer's count");
            } catch (TimeoutException expected) {
                // waiting on the customer's lock
            }
            c.commit();
            assertEquals("LIMIT", enroll.get(20, TimeUnit.SECONDS));
        }
        assertEquals(3, devicesOf(customerId));
        assertEquals("the refused enroll spends no use", 0, uses(tokenId));
    }

    @Test
    public void many_enrolls_through_two_codes_never_pass_the_device_limit() throws Exception {
        int customerId = customer(3);
        existingDevices(customerId, 2);
        int tokenA = token(5, customerId);
        int tokenB = token(5, customerId);
        List<Future<String>> all = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 6; i++) {
            int tokenId = i % 2 == 0 ? tokenA : tokenB;
            String attempt = "attempt-" + i + "-0123456789abcdef";
            all.add(pool.submit(() -> {
                start.await();
                try {
                    return enroll(tokenId, customerId, attempt).kind.name();
                } catch (AgentEnrollmentDAO.DeviceLimitException e) {
                    return "LIMIT";
                }
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<String> f : all) {
            if ("CREATED".equals(f.get(30, TimeUnit.SECONDS))) created++;
        }
        assertEquals("room for one more", 1, created);
        assertEquals(3, devicesOf(customerId));
        assertEquals(1, uses(tokenA) + uses(tokenB));
    }

    @Test
    public void a_resumed_enrollment_is_not_counted_again() throws Exception {
        int customerId = customer(2);
        existingDevices(customerId, 1);
        int tokenId = token(5, customerId);
        AgentEnrollmentDAO.Outcome first = enroll(tokenId, customerId, "attempt-0123456789abcdef");
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, first.kind);

        // The customer is now at its limit; the retry of that enrollment still gets its device.
        AgentEnrollmentDAO.Outcome retry = enroll(tokenId, customerId, "attempt-0123456789abcdef");
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.RESUMED, retry.kind);
        assertEquals(first.deviceNumber, retry.deviceNumber);
        assertEquals(2, devicesOf(customerId));
    }

    @Test
    public void a_single_customer_install_has_no_device_limit() throws Exception {
        // Customer 1 carries the schema's default limit of 3; like on-demand creation, it applies only once the
        // install has several customers.
        existingDevices(1, 3);
        int tokenId = token(1);
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, enroll(tokenId, "attempt-0123456789abcdef").kind);
    }

    /** Runs {@code statement} on its own connection, giving up after {@code ms} of lock waiting; true if it ran. */
    private boolean runsWithoutWaiting(String statement, int ms) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SET lock_timeout = '" + ms + "ms'");
            st.execute(statement);
            return true;
        } catch (java.sql.SQLException e) {
            if ("55P03".equals(e.getSQLState())) return false;
            throw e;
        }
    }

    /** Starts an enroll that stops inside its transaction (after the device insert) until released. */
    private Future<AgentEnrollmentDAO.Outcome> enrollHeldOpen(int tokenId, int customerId) throws Exception {
        Hooks.inside = new CountDownLatch(1);
        Hooks.proceed = new CountDownLatch(1);
        Future<AgentEnrollmentDAO.Outcome> f = pool.submit(() -> enroll(tokenId, customerId, "attempt-held-0123456789"));
        assertTrue("the enroll is inside its transaction", Hooks.inside.await(10, TimeUnit.SECONDS));
        return f;
    }

    @Test
    public void an_enroll_on_a_single_customer_install_blocks_no_other_insert_and_no_sign_in() throws Exception {
        int tokenId = token(1);
        Future<AgentEnrollmentDAO.Outcome> held = enrollHeldOpen(tokenId, 1);
        try {
            assertTrue("another device for the customer is added meanwhile", runsWithoutWaiting(
                    "INSERT INTO " + schema + ".devices (number, customerId) VALUES ('console-1', 1)", 1500));
            assertTrue("a sign-in records its time meanwhile", runsWithoutWaiting(
                    "UPDATE " + schema + ".customers SET lastLoginTime = 1 WHERE id = 1", 1500));
        } finally {
            Hooks.proceed.countDown();
        }
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, held.get(20, TimeUnit.SECONDS).kind);
    }

    @Test
    public void an_enroll_under_a_device_limit_blocks_no_other_insert_and_no_sign_in() throws Exception {
        int customerId = customer(5);
        int tokenId = token(5, customerId);
        Future<AgentEnrollmentDAO.Outcome> held = enrollHeldOpen(tokenId, customerId);
        try {
            assertTrue(runsWithoutWaiting("INSERT INTO " + schema + ".devices (number, customerId) VALUES "
                    + "('console-2', " + customerId + ")", 1500));
            assertTrue(runsWithoutWaiting("UPDATE " + schema + ".customers SET lastLoginTime = 1 WHERE id = "
                    + customerId, 1500));
        } finally {
            Hooks.proceed.countDown();
        }
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, held.get(20, TimeUnit.SECONDS).kind);
    }

    @Test
    public void a_dead_code_at_the_device_limit_gets_its_final_answer_not_the_limit() throws Exception {
        int customerId = customer(1);
        existingDevices(customerId, 1);
        int usedUp = token(1, customerId);
        sql("UPDATE " + schema + ".agentEnrollmentToken SET useCount = 1, used = true WHERE id = " + usedUp);
        int expired = token(5, customerId);
        sql("UPDATE " + schema + ".agentEnrollmentToken SET expiresAt = 1 WHERE id = " + expired);

        assertEquals(AgentEnrollmentDAO.Outcome.Kind.NO_USE, enroll(usedUp, customerId, null).kind);
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.NO_USE, enroll(expired, customerId, null).kind);
    }

    @Test
    public void a_master_customer_has_no_limit_and_a_negative_limit_blocks() throws Exception {
        int master = customer(1);
        sql("UPDATE " + schema + ".customers SET master = true WHERE id = " + master);
        existingDevices(master, 3);
        assertEquals(AgentEnrollmentDAO.Outcome.Kind.CREATED, enroll(token(1, master), master, null).kind);

        int negative = customer(-1);
        try {
            enroll(token(1, negative), negative, null);
            fail("a negative limit blocks, as in the console");
        } catch (AgentEnrollmentDAO.DeviceLimitException expected) {
            assertEquals(-1, expected.limit);
        }
    }

    @Test
    public void a_lock_wait_past_the_timeout_answers_retryably_and_keeps_nothing() throws Exception {
        int tokenId = token(1);
        enrollments.setLockTimeoutMs(300);
        com.hmdm.persistence.domain.AgentEnrollmentToken t = new com.hmdm.persistence.domain.AgentEnrollmentToken();
        t.setId(tokenId);
        t.setCustomerId(1);
        AgentEnrollmentTokenDAO tokens = new AgentEnrollmentTokenDAO(null) {
            @Override public com.hmdm.persistence.domain.AgentEnrollmentToken findByToken(String v) { return t; }
        };
        com.hmdm.rest.resource.AgentResource resource = new com.hmdm.rest.resource.AgentResource(
                null, tokens, null, null, null, null, null, null, null, enrollments);
        try (Connection c = connect(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.executeQuery("SELECT id FROM " + schema + ".agentEnrollmentToken WHERE id = " + tokenId + " FOR UPDATE")
                    .close();
            com.hmdm.rest.json.agent.AgentEnrollRequest req = new com.hmdm.rest.json.agent.AgentEnrollRequest();
            req.setEnrollToken("any");
            req.setEnrollAttempt("attempt-0123456789abcdef");
            // In the pool with a bound: if the lock timeout ever stopped applying, this fails instead of hanging.
            Future<com.hmdm.rest.json.Response> pending = pool.submit(() -> resource.enroll(req));
            com.hmdm.rest.json.Response r;
            try {
                r = pending.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                c.rollback(); // frees the waiting enroll so the pool can shut down
                throw new AssertionError("the enroll waited past its lock timeout");
            }
            assertEquals("error.agent.enrollment.inProgress", r.getMessage());
            c.rollback();
        }
        assertEquals("nothing kept", 0, devices());
        assertEquals("no use spent", 0, uses(tokenId));
    }

    @Test
    public void devices_enrolled_since_are_filtered_by_access_before_the_limit() throws Exception {
        int customerId = customer(0);
        sql("INSERT INTO " + schema + ".users (id, customerId, allDevicesAvailable) VALUES (7, " + customerId
                + ", false)", "INSERT INTO " + schema + ".userDeviceGroupsAccess (userId, groupId) VALUES (7, 1)");
        sql("INSERT INTO " + schema + ".devices (number, customerId, enrollTime, lastUpdate) VALUES ('mine', "
                + customerId + ", 1000, 1000)");
        sql("INSERT INTO " + schema + ".deviceGroups (deviceId, groupId) SELECT id, 1 FROM " + schema
                + ".devices WHERE number = 'mine'");
        for (int i = 0; i < 30; i++) {
            sql("INSERT INTO " + schema + ".devices (number, customerId, enrollTime, lastUpdate) VALUES ('other-" + i
                    + "', " + customerId + ", " + (2000 + i) + ", 0)");
        }
        List<Device> mine = deviceMapper.listEnrolledSince(customerId, 7, false, 0, 25);
        assertEquals(1, mine.size());
        assertEquals("mine", mine.get(0).getNumber());
        assertEquals("a super admin sees them all, still bounded", 25,
                deviceMapper.listEnrolledSince(customerId, 7, true, 0, 25).size());
    }
}
