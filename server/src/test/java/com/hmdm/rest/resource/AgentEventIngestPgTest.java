/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.mapper.AgentCommandMapper;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.DeviceEventMapper;
import com.hmdm.persistence.mapper.DeviceStateMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCheckInResponse;
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
 * lengths are PostgreSQL's own. Two hooks are added: device_event refuses a detail of {@code refuse-me} (a CHECK
 * constraint, SQL state 23514) and fails a detail of {@code blip} as a deadlock would (40P01, not the data's fault).
 * Skipped without MDMESH_TEST_PG_URL; fails without it when MDMESH_TEST_PG_REQUIRED is set (CI).
 */
public class AgentEventIngestPgTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    /** The real event and kiosk-return statements; everything else a check-in touches does nothing. */
    public static class EventsOnlyDAO extends AgentCommandDAO {
        @Inject
        public EventsOnlyDAO(AgentCommandMapper mapper, AgentDeviceMapper deviceMapper, DeviceStateMapper stateMapper,
                             DeviceEventMapper eventMapper) {
            super(mapper, deviceMapper, stateMapper, eventMapper);
        }
        @Override public String getDeviceSecretHash(String n) {
            return CryptoUtil.getSHA256String(AgentCheckInLimitsTest.SECRET);
        }
        @Override public void touchLastUpdate(String n) { }
        @Override public void expireStale(String n, long a, long b) { }
        @Override public String getDeviceCapabilities(String n) { return "{\"device\":[\"lock\"]}"; }
        @Override public List<AgentCommand> listPending(String n) { return Collections.emptyList(); }
    }

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
        sql("ALTER TABLE " + schema + ".device_event ADD CONSTRAINT refuse_me "
                        + "CHECK (detail IS DISTINCT FROM 'refuse-me')",
                "CREATE FUNCTION " + schema + ".blip() RETURNS trigger AS $$ BEGIN "
                        + "IF NEW.detail = 'blip' THEN RAISE EXCEPTION 'blip' USING ERRCODE = '40P01'; END IF; "
                        + "RETURN NEW; END $$ LANGUAGE plpgsql",
                "CREATE TRIGGER blip BEFORE INSERT ON " + schema + ".device_event FOR EACH ROW EXECUTE PROCEDURE "
                        + schema + ".blip()");

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
                bind(AgentCommandDAO.class).to(EventsOnlyDAO.class);
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
        sql("TRUNCATE " + schema + ".device_event, " + schema + ".device_state");
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
        return resource.checkin("Bearer " + AgentCheckInLimitsTest.SECRET, null, req);
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
        Response r = resource.checkin("Bearer " + AgentCheckInLimitsTest.SECRET, null, req);
        assertEquals(field.getName(), Response.ResponseStatus.OK, r.getStatus());
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT " + field.getName() + " FROM " + schema + ".device_state")) {
            assertTrue(rs.next());
            return rs.getString(1);
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
}
