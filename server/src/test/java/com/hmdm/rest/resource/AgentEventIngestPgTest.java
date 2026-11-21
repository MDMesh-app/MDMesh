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
import com.hmdm.rest.json.agent.AgentTelemetryEvent;
import com.hmdm.rest.resource.support.AppReconciler;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskLockoutAlert;
import com.hmdm.util.CryptoUtil;
import org.apache.ibatis.exceptions.PersistenceException;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A check-in's events against a real PostgreSQL, through mybatis-guice as the server builds it, so the batch's
 * transaction, its rollback and the row-by-row fallback are PostgreSQL's own. The test table refuses a detail of
 * {@code refuse-me} (a CHECK constraint, SQL state 23514) and fails a detail of {@code blip} as a deadlock would
 * (40P01, not the data's fault). Skipped without MDMESH_TEST_PG_URL; fails without it when MDMESH_TEST_PG_REQUIRED is
 * set (CI).
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

    private String schema;
    private AgentResource resource;

    @Before
    public void setUp() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "events_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema,
                // device_event and device_state as the changelog makes them, plus the two failure hooks.
                "CREATE TABLE " + schema + ".device_event (id BIGSERIAL PRIMARY KEY, "
                        + "deviceNumber VARCHAR(255) NOT NULL, type VARCHAR(64) NOT NULL, ts BIGINT NOT NULL, detail TEXT, "
                        + "CHECK (detail IS DISTINCT FROM 'refuse-me'))",
                "CREATE FUNCTION " + schema + ".blip() RETURNS trigger AS $$ BEGIN "
                        + "IF NEW.detail = 'blip' THEN RAISE EXCEPTION 'blip' USING ERRCODE = '40P01'; END IF; "
                        + "RETURN NEW; END $$ LANGUAGE plpgsql",
                "CREATE TRIGGER blip BEFORE INSERT ON " + schema + ".device_event FOR EACH ROW EXECUTE PROCEDURE "
                        + schema + ".blip()",
                "CREATE TABLE " + schema + ".device_state (deviceNumber VARCHAR(255) PRIMARY KEY, "
                        + "updatedAt BIGINT NOT NULL, kioskGeneration BIGINT NOT NULL DEFAULT 0, "
                        + "kioskReturnGeneration BIGINT)");

        Properties props = new Properties();
        props.setProperty("JDBC.driver", "org.postgresql.Driver");
        props.setProperty("JDBC.url", URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        props.setProperty("JDBC.username", USER == null ? "postgres" : USER);
        props.setProperty("JDBC.password", PASSWORD == null ? "" : PASSWORD);
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

    @After
    public void tearDown() throws Exception {
        if (schema != null && URL != null && !URL.isEmpty()) sql("DROP SCHEMA " + schema + " CASCADE");
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(URL, USER == null ? "postgres" : USER, PASSWORD == null ? "" : PASSWORD);
    }

    private void sql(String... statements) throws Exception {
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
}
