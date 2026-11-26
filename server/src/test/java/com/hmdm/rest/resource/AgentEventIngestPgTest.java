/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.mapper.AgentCommandMapper;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.DeviceEventMapper;
import com.hmdm.persistence.mapper.DeviceStateMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCheckInResponse;
import com.hmdm.rest.json.agent.AgentCommandResult;
import com.hmdm.rest.json.agent.AgentDeviceState;
import com.hmdm.rest.json.agent.AgentTelemetryEvent;
import com.hmdm.rest.resource.support.AppReconciler;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskLockoutAlert;
import com.hmdm.util.CryptoUtil;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.guice.MyBatisModule;
import org.mybatis.guice.datasource.builtin.PooledDataSourceProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A check-in's events and state against a real PostgreSQL, on the schema the Liquibase changelog makes, through
 * mybatis-guice as the server builds it: the batch's transaction, its rollback, the row-by-row fallback and the column
 * lengths are PostgreSQL's own. Three hooks are added: device_event refuses a detail of {@code refuse-me} (a CHECK
 * constraint, SQL state 23514) and fails a detail of {@code blip} as a deadlock would (40P01, not the data's fault),
 * and device_state fails with the SQL state a check-in gives as its power mode.
 * Skipped without MDMESH_TEST_PG_URL; fails without it when MDMESH_TEST_PG_REQUIRED is set (CI).
 */
public class AgentEventIngestPgTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    private static String schema;
    private static AgentResource resource;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "events_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema);
        String url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection c = DriverManager.getConnection(url, user(), password())) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("liquibase/db.changelog.xml", new ClassLoaderResourceAccessor(), db).update("common,private");
        }
        // The device the check-ins come from: its history rows reference devices(number).
        sql("INSERT INTO " + schema + ".customers (name, filesDir, prefix) VALUES ('events-it', 'events-it', "
                        + "'events-it')",
                "INSERT INTO " + schema + ".configurations (name, customerId) SELECT 'events-it', id FROM " + schema
                        + ".customers WHERE name = 'events-it'",
                "INSERT INTO " + schema + ".devices (number, customerId, configurationId, lastUpdate, "
                        + "agentSecretHash) SELECT '" + AgentCheckInLimitsTest.DEVICE + "', customerId, id, 0, '"
                        + CryptoUtil.getSHA256String(AgentCheckInLimitsTest.SECRET) + "' FROM " + schema
                        + ".configurations WHERE name = 'events-it'");
        sql("ALTER TABLE " + schema + ".device_event ADD CONSTRAINT refuse_me "
                        + "CHECK (detail IS DISTINCT FROM 'refuse-me')",
                "CREATE FUNCTION " + schema + ".blip() RETURNS trigger AS $$ BEGIN "
                        + "IF NEW.detail = 'blip' THEN RAISE EXCEPTION 'blip' USING ERRCODE = '40P01'; END IF; "
                        + "RETURN NEW; END $$ LANGUAGE plpgsql",
                "CREATE TRIGGER blip BEFORE INSERT ON " + schema + ".device_event FOR EACH ROW EXECUTE PROCEDURE "
                        + schema + ".blip()",
                "CREATE FUNCTION " + schema + ".refuse_state() RETURNS trigger AS $$ BEGIN "
                        + "IF NEW.powerMode ~ '^[0-9A-Z]{5}$' THEN RAISE EXCEPTION 'refused' "
                        + "USING ERRCODE = NEW.powerMode; END IF; RETURN NEW; END $$ LANGUAGE plpgsql",
                "CREATE TRIGGER refuse_state BEFORE INSERT ON " + schema + ".device_state FOR EACH ROW EXECUTE "
                        + "PROCEDURE " + schema + ".refuse_state()");

        Properties props = new Properties();
        props.setProperty("JDBC.driver", "org.postgresql.Driver");
        props.setProperty("JDBC.url", url);
        props.setProperty("JDBC.username", user());
        props.setProperty("JDBC.password", password());
        Injector injector = Guice.createInjector(new MyBatisModule() {
            @Override
            protected void initialize() {
                environmentId("test");
                Names.bindProperties(binder(), props);
                bindDataSourceProviderType(PooledDataSourceProvider.class);
                bindTransactionFactoryType(JdbcTransactionFactory.class);
                addMapperClass(AgentCommandMapper.class);
                addMapperClass(AgentDeviceMapper.class);
                addMapperClass(DeviceStateMapper.class);
                addMapperClass(DeviceEventMapper.class);
            }
        });
        resource = new AgentResource(new AgentCheckInLimitsTest.FakeUnsecureDAO(), null,
                injector.getInstance(AgentCommandDAO.class), new ConfigAppInstaller(null, null, null, null),
                new AgentCheckInLimitsTest.NoopReconciler(), new AppReconciler(null, null, null, null),
                new AgentCheckInLimitsTest.NoopRequeuer(), new InstallHashes(null, null),
                new KioskLockoutAlert(null, null, null), null);
    }

    @Before
    public void emptyTables() throws Exception {
        sql("TRUNCATE " + schema + ".device_event, " + schema + ".device_state, " + schema + ".agentCommand",
                "UPDATE " + schema + ".devices SET lastUpdate = 0");
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (schema != null) sql("DROP SCHEMA " + schema + " CASCADE");
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

    /** The stored events' types, in insert order. */
    private List<String> stored() throws Exception {
        List<String> types = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT type FROM " + schema + ".device_event ORDER BY id")) {
            while (r.next()) types.add(r.getString(1));
        }
        return types;
    }

    private boolean kioskReturnRecorded() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT count(*) FROM " + schema + ".device_state WHERE kioskReturnGeneration IS NOT NULL")) {
            r.next();
            return r.getLong(1) == 1;
        }
    }

    private static AgentTelemetryEvent event(String type, String detail) {
        AgentTelemetryEvent e = new AgentTelemetryEvent();
        e.setType(type);
        e.setTs(1L);
        e.setDetail(detail);
        return e;
    }

    private Response checkin(String protocolVersion, AgentTelemetryEvent... events) {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(AgentCheckInLimitsTest.DEVICE);
        req.setProtocolVersion(protocolVersion);
        req.setEvents(Arrays.asList(events));
        return checkin(req);
    }

    private static int eventsAccepted(Response r) {
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        return ((AgentCheckInResponse) r.getData()).getEventsAccepted();
    }

    @Test
    public void storesTheBatchWithItsKioskReturn() throws Exception {
        Response r = checkin("1.12", event("boot", null), event("kioskReturn", "x"), event("lowBattery", "12%"));
        assertEquals(3, eventsAccepted(r));
        assertEquals(Arrays.asList("boot", "kioskReturn", "lowBattery"), stored());
        assertTrue(kioskReturnRecorded());
    }

    @Test
    public void aRefusedRowIsSkippedAndCountedAndTheRestStoredOnce() throws Exception {
        Response r = checkin("1.12", event("boot", null), event("kioskReturn", "x"), event("lowBattery", "refuse-me"),
                event("connectivityChange", "wifi"));
        assertEquals(4, eventsAccepted(r));
        // The refused batch rolled back, so the fallback stores each of the others exactly once.
        assertEquals(Arrays.asList("boot", "kioskReturn", "connectivityChange"), stored());
        assertTrue(kioskReturnRecorded());
    }

    @Test
    public void aBlipInTheFallbackCountsTheEventsBeforeIt() throws Exception {
        Response r = checkin("1.12", event("boot", null), event("lowBattery", "refuse-me"), event("kioskReturn", "x"),
                event("connectivityChange", "blip"), event("boot", "after"));
        // boot stored, lowBattery refused, kioskReturn stored; the blip stops there: the agent resends from it.
        assertEquals(3, eventsAccepted(r));
        assertEquals(Arrays.asList("boot", "kioskReturn"), stored());
    }

    @Test
    public void aBlipInTheBatchStoresNothingAndCountsNothing() throws Exception {
        Response r = checkin("1.12", event("boot", null), event("lowBattery", "blip"));
        assertEquals(0, eventsAccepted(r));
        assertEquals(Collections.emptyList(), stored());
    }

    @Test
    public void aBlipFailsTheCheckInOfAnOlderAgentAndStoresNothing() throws Exception {
        try {
            checkin(null, event("boot", null), event("lowBattery", "blip"));
            fail("an agent before 1.12 drops its whole batch on success: the check-in must fail");
        } catch (PersistenceException expected) {
            assertEquals(Collections.emptyList(), stored());
        }
    }

    @Test
    public void theBatchIsOneTransaction() throws Exception {
        // The kiosk-return evidence is refused after the batch's rows went in: they must go with it, or the fallback
        // stores them a second time.
        sql("ALTER TABLE " + schema + ".device_state ADD CONSTRAINT no_kiosk_return "
                + "CHECK (kioskReturnGeneration IS NULL)");
        try {
            Response r = checkin("1.12", event("boot", null), event("kioskReturn", "x"), event("lowBattery", "12%"));
            assertEquals(3, eventsAccepted(r));
            // The kioskReturn and its evidence are refused together (recordKioskReturn's own transaction).
            assertEquals(Arrays.asList("boot", "lowBattery"), stored());
        } finally {
            sql("ALTER TABLE " + schema + ".device_state DROP CONSTRAINT no_kiosk_return");
        }
    }

    /** The device-sent state strings AgentResource has a storage rule for (storable, validRevision). */
    private static final Set<String> STATE_STRINGS_WITH_A_RULE = new HashSet<>(
            Arrays.asList("androidRelease", "agentVersion", "powerMode", "appliedConfigRevision"));

    /** The device_state column's length, as the changelog left it. */
    private static int columnLength(String column) throws Exception {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT character_maximum_length "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = 'device_state' "
                + "AND column_name = ?")) {
            s.setString(1, schema);
            s.setString(2, column.toLowerCase(Locale.ROOT));
            try (ResultSet r = s.executeQuery()) {
                assertTrue("device_state has no column " + column, r.next());
                int length = r.getInt(1);
                assertFalse(column + " is not a VARCHAR(n)", r.wasNull());
                return length;
            }
        }
    }

    /**
     * Checks in with only {@code field} of the state set to {@code value}; returns what device_state holds. From an
     * empty table: the upsert keeps an earlier revision when a check-in reports none it can store.
     */
    private static String storedState(Field field, String value) throws Exception {
        sql("TRUNCATE " + schema + ".device_state");
        AgentDeviceState state = new AgentDeviceState();
        field.setAccessible(true);
        field.set(state, value);
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(AgentCheckInLimitsTest.DEVICE);
        req.setState(state);
        Response r = checkin(req);
        assertEquals(field.getName(), Response.ResponseStatus.OK, r.getStatus());
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT " + field.getName() + " FROM " + schema + ".device_state")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    public void aSnapshotJsonbCannotHoldIsStoredWholeAndItsImeisSearchable() throws Exception {
        // Telemetry is stored as the text the agent sent. A value JSONB can't hold (an SSID is any bytes, so an
        // escaped NUL is real) must not cost the device its snapshot, nor its IMEIs in the search.
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        List<ObjectNode> incompatible = Arrays.asList(
                nodes.objectNode().put("ssid", "a\0b"),
                nodes.objectNode().put("bad\0key", "model"),
                nodes.objectNode().put("value", new BigDecimal("1e1000000")));
        for (ObjectNode telemetry : incompatible) {
            telemetry.putObject("identity").putArray("imei").add("359881234567890").add("359889876543210");
            String stored = storedTelemetry(telemetry);
            assertEquals(telemetry.toString(), stored);
            assertEquals(Arrays.asList("359881234567890", "359889876543210"), storedImeis());
        }
    }

    @Test
    public void eachCheckInReplacesTheReportedImeis() throws Exception {
        ObjectNode telemetry = JsonNodeFactory.instance.objectNode();
        telemetry.putObject("identity").putArray("imei").add("359881234567890");
        storedTelemetry(telemetry);
        assertEquals(Collections.singletonList("359881234567890"), storedImeis());
        telemetry.putObject("identity").putArray("imei").add("359889876543210");
        storedTelemetry(telemetry);
        assertEquals(Collections.singletonList("359889876543210"), storedImeis());
        storedTelemetry(JsonNodeFactory.instance.objectNode());
        assertNull(storedImeis());
    }

    /** device_state.reportedImeis of the test device. */
    private static List<String> storedImeis() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT reportedImeis FROM " + schema + ".device_state")) {
            assertTrue(rs.next());
            java.sql.Array imeis = rs.getArray(1);
            return imeis == null ? null : Arrays.asList((String[]) imeis.getArray());
        }
    }

    @Test
    public void compatibleTelemetryKeepsLiteralEscapesAndUnicode() throws Exception {
        ObjectNode telemetry = JsonNodeFactory.instance.objectNode();
        telemetry.put("literal", "\\u0000");
        telemetry.put("literal\\u0000key", "a\\b");
        telemetry.put("unicode", "ñ\ud83d\ude00\n");
        telemetry.putObject("identity").putArray("imei").add("359881234567890");
        assertEquals(telemetry, new ObjectMapper().readTree(storedTelemetry(telemetry)));
        assertNull(storedTelemetry(null));
    }

    private static String storedTelemetry(JsonNode telemetry) throws Exception {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(AgentCheckInLimitsTest.DEVICE);
        AgentDeviceState state = new AgentDeviceState();
        state.setBattery(73);
        req.setState(state);
        req.setTelemetry(telemetry);
        assertEquals(Response.ResponseStatus.OK, checkin(req).getStatus());
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT battery, telemetry FROM " + schema + ".device_state")) {
            assertTrue(rs.next());
            assertEquals(73, rs.getInt("battery"));
            String stored = rs.getString("telemetry");
            assertFalse(rs.next());
            return stored;
        }
    }

    @Test
    public void everyStateStringFitsItsColumnOrIsIgnored() throws Exception {
        // A value the column can't hold would fail every check-in from the device. A new state field must get a rule
        // here and in AgentResource.
        for (Field f : AgentDeviceState.class.getDeclaredFields()) {
            if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
            assertTrue("AgentDeviceState." + f.getName() + " has no storage rule",
                    STATE_STRINGS_WITH_A_RULE.contains(f.getName()));
            int max = columnLength(f.getName());
            // 'a' is a hex digit, so a full-length value is a well-formed revision too.
            String longest = new String(new char[max]).replace('\0', 'a');
            assertEquals(f.getName() + " fits its column", longest, storedState(f, longest));
            assertNull(f.getName() + " one too long", storedState(f, longest + "a"));
            assertNull(f.getName() + " with a NUL", storedState(f, "a\0a"));
        }
    }

    /** A check-in whose state makes device_state fail with {@code sqlState}; null stores it. */
    private static AgentCheckInRequest checkinWithState(String sqlState, int battery) {
        AgentDeviceState state = new AgentDeviceState();
        state.setBattery(battery);
        state.setAndroidRelease("16");
        state.setPowerMode(sqlState);
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setDeviceId(AgentCheckInLimitsTest.DEVICE);
        req.setProtocolVersion("1.12");
        req.setState(state);
        req.setEvents(Collections.singletonList(event("boot", null)));
        return req;
    }

    private static Response checkin(AgentCheckInRequest req) {
        return resource.checkin("Bearer " + AgentCheckInLimitsTest.SECRET, null, req);
    }

    private static void queueCommand() throws Exception {
        sql("INSERT INTO " + schema + ".agentCommand (deviceNumber, type, status, createdAt) VALUES ('"
                + AgentCheckInLimitsTest.DEVICE + "', 'device.lock', 'pending', " + System.currentTimeMillis() + ")");
    }

    /** {@code value} of the test device as text, the first row's. */
    private static String value(String value, String table) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT (" + value + ")::text FROM " + schema + "." + table)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    @Test
    public void aStateTheDatabaseRefusesIsSkippedAndTheCheckInGoesOn() throws Exception {
        for (String sqlState : Arrays.asList("22001", "22003", "22P05", "23514")) {
            emptyTables();
            checkin(checkinWithState(null, 73));
            sql("UPDATE " + schema + ".devices SET lastUpdate = 0");
            queueCommand();

            Response r = checkin(checkinWithState(sqlState, 12));

            assertEquals(sqlState, Response.ResponseStatus.OK, r.getStatus());
            assertEquals(sqlState, 1, ((AgentCheckInResponse) r.getData()).getCommands().size());
            assertEquals(sqlState, "delivered", value("status", "agentCommand"));
            assertEquals(sqlState, Arrays.asList("boot", "boot"), stored());
            assertEquals(sqlState + ": the last state it could store stays", "73", value("battery", "device_state"));
            assertEquals(sqlState + ": it took its commands, so it is online", "true",
                    value("lastUpdate > 0", "devices"));
        }
    }

    @Test
    public void aDatabaseErrorInTheStateWriteFailsTheCheckInAndDoesNotShowTheDeviceOnline() throws Exception {
        for (String sqlState : Arrays.asList("08006", "53200", "57014", "57P03", "40P01", "42703", "25P02")) {
            emptyTables();
            queueCommand();
            try {
                checkin(checkinWithState(sqlState, 12));
                fail(sqlState + " is not the state's fault: the check-in must fail");
            } catch (PersistenceException expected) {
                assertEquals(sqlState, "0", value("lastUpdate", "devices"));
                assertEquals(sqlState, "pending", value("status", "agentCommand"));
            }
        }
    }

    @Test
    public void aRefusedStateLeavesAConcurrentCheckInAlone() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                emptyTables();
                queueCommand();
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                List<java.util.concurrent.Future<Response>> both = new ArrayList<>();
                for (String sqlState : new String[]{"22001", null}) {
                    both.add(pool.submit(() -> {
                        start.await();
                        return checkin(checkinWithState(sqlState, 73));
                    }));
                }
                int delivered = 0;
                for (java.util.concurrent.Future<Response> one : both) {
                    Response r = one.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(Response.ResponseStatus.OK, r.getStatus());
                    delivered += ((AgentCheckInResponse) r.getData()).getCommands().size();
                }
                assertEquals("delivered once", 1, delivered);
                assertEquals("73", value("battery", "device_state"));
                assertEquals(Arrays.asList("boot", "boot"), stored());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void noStringADeviceSendsFailsItsCheckInOrCostsItItsState() throws Exception {
        // deviceId only looks the device up. Every other String of the request and its parts, as it comes: longer than
        // any column, or with the NUL PostgreSQL refuses. A refused state would be skipped, so its row must be there.
        String tooLong = new String(new char[AgentResource.MAX_RESULT_DETAIL_CHARS + 1]).replace('\0', 'a');
        for (Class<?> part : Arrays.asList(AgentCheckInRequest.class, AgentDeviceState.class, AgentCommandResult.class,
                AgentTelemetryEvent.class)) {
            for (Field f : part.getDeclaredFields()) {
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())
                        || f.getName().equals("deviceId")) continue;
                for (String hostile : new String[]{tooLong, "a\0a", "a\ud800"}) {
                    emptyTables();
                    queueCommand();
                    AgentCheckInRequest req = checkinWithState(null, 73);
                    AgentCommandResult result = new AgentCommandResult();
                    result.setCommandId(value("id", "agentCommand"));
                    result.setStatus("accepted");
                    req.setResults(Collections.singletonList(result));
                    f.setAccessible(true);
                    f.set(part == AgentCheckInRequest.class ? req : part == AgentDeviceState.class ? req.getState()
                            : part == AgentCommandResult.class ? result : req.getEvents().get(0), hostile);

                    String what = part.getSimpleName() + "." + f.getName();
                    assertEquals(what, Response.ResponseStatus.OK, checkin(req).getStatus());
                    assertEquals(what, "73", value("battery", "device_state"));
                }
            }
        }
    }
}
