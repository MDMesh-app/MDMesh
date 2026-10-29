package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.RolloutDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceSyncRow;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.rest.json.AgentBulkCommandRequest;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.ConfigSyncSummary;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.RolloutRequeuer;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A user limited to some device groups can neither command nor read the agent data of a device outside them.
 */
public class AgentDeviceAccessTest {

    private static final int CUSTOMER = 1;
    private static final String VISIBLE = "IN-MY-GROUP";
    private static final String HIDDEN = "NOT-MY-GROUP";
    private static final int VISIBLE_ID = 1, HIDDEN_ID = 2;

    private final Map<String, Device> devices = new HashMap<>();
    private final List<String> sideEffects = new ArrayList<>();
    private boolean allDevices;
    private boolean active;
    private FakeMapper<DeviceMapper> deviceMapper;
    private AgentAdminResource resource;
    private RolloutResource rollouts;

    private static Device device(int id, String number) {
        Device d = new Device();
        d.setId(id);
        d.setNumber(number);
        d.setCustomerId(CUSTOMER);
        d.setConfigurationId(5);
        return d;
    }

    @Before
    public void setUp() throws Exception {
        devices.put(VISIBLE, device(VISIBLE_ID, VISIBLE));
        devices.put(HIDDEN, device(HIDDEN_ID, HIDDEN));

        // What the group-aware SQL answers for the logged-in user.
        deviceMapper = new FakeMapper<>(DeviceMapper.class)
                .answer("isDeviceAccessibleToUser", a -> allDevices || ((Integer) a[0]) == VISIBLE_ID)
                .answer("isCustomerConfiguration", a -> ((Integer) a[0]) == OWN_CONFIG || ((Integer) a[0]) == OTHER_OWN_CONFIG)
                .answer("getDeviceNumbersAccessibleToUser",
                        a -> allDevices ? Fakes.list(VISIBLE, HIDDEN) : Fakes.list(VISIBLE));
        DeviceDAO deviceDAO = new DeviceDAO(deviceMapper.mapper, null, Guice.createInjector(), null, 3);

        UnsecureDAO unsecureDAO = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, "files", 2, "launcher") {
            @Override public Device getDeviceByNumber(String number) { return devices.get(number); }
            @Override public Device getDeviceById(Integer id) {
                return devices.values().stream().filter(d -> d.getId().equals(id)).findFirst().orElse(null);
            }
            @Override public Configuration getConfigurationById(Integer id) {
                if (id == null || (id != OWN_CONFIG && id != OTHER_OWN_CONFIG)) return null;
                Configuration c = new Configuration();
                c.setId(id);
                c.setCustomerId(CUSTOMER);
                return c;
            }
        };
        AgentCommandDAO commandDAO = new AgentCommandDAO(null, null, null, null) {
            @Override public void insert(AgentCommand c) { sideEffects.add("insert " + c.getDeviceNumber()); }
            @Override public DeviceState getState(String n) { return new DeviceState(); }
            @Override public List listEvents(String n, long s, int l) { return new ArrayList<>(); }
            @Override public List<AgentCommand> listHistory(String n, long s, int l) { return new ArrayList<>(); }
            @Override public List listLocations(String n, long s, int l) { return new ArrayList<>(); }
            @Override public String getDeviceCapabilities(String n) { return null; }
            @Override public AgentCommand findLatestOfType(String n, String t) { return null; }
            @Override public List<DeviceSyncRow> listDevicesForSync(int customerId) {
                List<DeviceSyncRow> rows = new ArrayList<>();
                for (String n : Arrays.asList(VISIBLE, HIDDEN)) {
                    DeviceSyncRow r = new DeviceSyncRow();
                    r.setDeviceNumber(n);
                    r.setConfigurationId(5);
                    r.setCapabilitiesJson("{\"device\":[\"configApply\"]}");
                    r.setAppliedConfigRevision("rev");
                    r.setLastApplyDeferred(true);
                    rows.add(r);
                }
                return rows;
            }
        };
        AgentWakeHub wakeHub = new AgentWakeHub(commandDAO) {
            @Override public void wake(String n, String kind) { sideEffects.add("wake " + n); }
        };
        ConfigAppInstaller installer = new ConfigAppInstaller(unsecureDAO, commandDAO, wakeHub, null) {
            @Override public int enqueueConfigApps(Device d) { sideEffects.add("syncApps " + d.getNumber()); return 1; }
        };
        ConfigReconciler reconciler = new ConfigReconciler(unsecureDAO, commandDAO) {
            @Override public String currentRevision(Device d) { return "rev"; }
        };

        RolloutDAO rolloutDAO = new RolloutDAO(null) {
            @Override public AgentRollout findActiveByCustomer(int c) {
                if (!active) return null;
                AgentRollout r = findById(77);
                r.setStage("fleet");
                return r;
            }
            @Override public void insertRollout(AgentRollout r) { r.setId(77); sideEffects.add("rollout created"); }
            @Override public void insertCanary(int id, String n) { sideEffects.add("canary " + n); }
            @Override public AgentRollout findById(int id) {
                AgentRollout r = new AgentRollout();
                r.setId(id);
                r.setCustomerId(CUSTOMER);
                r.setStage("canary");
                r.setTargetVersion("2");
                r.setPackageName("com.example.agent");
                return r;
            }
            @Override public void updateStage(int id, String stage) { sideEffects.add("stage " + stage); }
            @Override public List<String> listCanaryNumbers(int id) { return new ArrayList<>(); }
            @Override public List<RolloutDeviceRow> listCustomerDevices(int c) {
                List<RolloutDeviceRow> rows = new ArrayList<>();
                for (String n : Arrays.asList(VISIBLE, HIDDEN)) {
                    RolloutDeviceRow r = new RolloutDeviceRow();
                    r.setDeviceNumber(n);
                    rows.add(r);
                }
                return rows;
            }
            @Override public Set<String> listPendingInstallNumbers(int c, String p) { return new HashSet<>(); }
        };
        RolloutRequeuer requeuer = new RolloutRequeuer(rolloutDAO) {
            @Override public boolean enqueue(AgentRollout r, String n, long now) { sideEffects.add("rollout " + n); return true; }
        };

        Map<Class<?>, Object> deps = new HashMap<>();
        deps.put(DeviceDAO.class, deviceDAO);
        deps.put(AgentEnrollmentTokenDAO.class, new AgentEnrollmentTokenDAO(null) {
            @Override public void insert(AgentEnrollmentToken t) { sideEffects.add("token " + t.getConfigurationId()); }
        });
        deps.put(UnsecureDAO.class, unsecureDAO);
        deps.put(AgentCommandDAO.class, commandDAO);
        deps.put(AgentWakeHub.class, wakeHub);
        deps.put(ConfigAppInstaller.class, installer);
        deps.put(ConfigReconciler.class, reconciler);
        deps.put(RolloutDAO.class, rolloutDAO);
        deps.put(RolloutRequeuer.class, requeuer);
        deps.put(InstallHashes.class, new InstallHashes(null, unsecureDAO));
        deps.put(String.class, "https://mdm.example.com");
        resource = construct(AgentAdminResource.class, deps);
        rollouts = construct(RolloutResource.class, deps);

        login(false);
    }

    /** Builds the resource through its injection constructor, whatever collaborators it declares. */
    private static <T> T construct(Class<T> type, Map<Class<?>, Object> deps) throws Exception {
        Constructor<?> best = null;
        for (Constructor<?> c : type.getConstructors()) {
            if (best == null || c.getParameterCount() > best.getParameterCount()) best = c;
        }
        Object[] args = new Object[best.getParameterCount()];
        Class<?>[] types = best.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = deps.get(types[i]);
        }
        return type.cast(best.newInstance(args));
    }

    private static final int OWN_CONFIG = 5, OTHER_OWN_CONFIG = 6;

    private void login(User u) {
        SecurityContext.release();
        SecurityContext.init(u);
    }

    private void login(boolean allDevicesAvailable) {
        allDevices = allDevicesAvailable;
        User u = Fakes.user(9, CUSTOMER, "edit_devices", "devices");
        u.setAllDevicesAvailable(allDevicesAvailable);
        SecurityContext.release();
        SecurityContext.init(u);
    }

    private Response mint(Integer configurationId) {
        AgentEnrollmentToken body = new AgentEnrollmentToken();
        body.setConfigurationId(configurationId);
        return resource.mintToken(body);
    }

    @Test
    public void aTokenCannotBindAConfigurationOutsideTheUsersList() {
        login(Fakes.limitedToConfigurations(Fakes.user(9, CUSTOMER, "edit_devices", "devices"), OWN_CONFIG));
        assertNotEquals(Response.ResponseStatus.OK, mint(OTHER_OWN_CONFIG).getStatus());
        assertEquals(Collections.emptyList(), sideEffects);

        assertEquals(Response.ResponseStatus.OK, mint(OWN_CONFIG).getStatus());
        assertEquals(Response.ResponseStatus.OK, mint(null).getStatus());
    }

    @Test
    public void aUserWithAllConfigurationsMayBindAnyOfTheCustomers() {
        assertEquals(Response.ResponseStatus.OK, mint(OTHER_OWN_CONFIG).getStatus());
        assertNotEquals(Response.ResponseStatus.OK, mint(99).getStatus());
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private Map<String, Supplier<Response>> perDeviceRoutes(String number) {
        Map<String, Supplier<Response>> routes = new HashMap<>();
        AgentCommand cmd = new AgentCommand();
        cmd.setType("device.lock");
        routes.put("syncApps", () -> resource.syncConfigApps(number));
        routes.put("queueCommand", () -> resource.queueCommand(number, cmd));
        routes.put("state", () -> resource.getState(number));
        routes.put("configStatus", () -> resource.getConfigStatus(number));
        routes.put("telemetry", () -> resource.getTelemetry(number));
        routes.put("events", () -> resource.listEvents(number, null, null));
        routes.put("commands", () -> resource.listCommands(number, null));
        routes.put("locations", () -> resource.listLocations(number, null));
        routes.put("forceSync", () -> resource.forceSync(number));
        return routes;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void configApplyIsServerOwnedAndRejectedBeforeAnythingIsQueued() {
        // The server queues config.apply from the device's configuration, at most one open per device. An admin one
        // would hit that limit halfway through a bulk fan-out, so it is refused up front, whatever its case.
        for (String type : new String[]{"config.apply", " Config.Apply "}) {
            AgentCommand cmd = new AgentCommand();
            cmd.setType(type);
            Response one = resource.queueCommand(VISIBLE, cmd);
            assertEquals(Response.ResponseStatus.ERROR, one.getStatus());
            assertEquals("error.agent.command.serverOwned", one.getMessage());

            AgentBulkCommandRequest req = new AgentBulkCommandRequest();
            req.setDeviceIds(Fakes.list(VISIBLE_ID, HIDDEN_ID));
            req.setCommand(cmd);
            Response bulk = resource.queueCommandBulk(req);
            assertEquals(Response.ResponseStatus.ERROR, bulk.getStatus());
            assertEquals("error.agent.command.serverOwned", bulk.getMessage());
        }
        login(true);
        AgentCommand cmd = new AgentCommand();
        cmd.setType("config.apply");
        assertEquals("error.agent.command.serverOwned", resource.queueCommand(HIDDEN, cmd).getMessage());
        assertEquals("nothing queued or woken", Collections.emptyList(), sideEffects);
    }

    @Test
    public void everyPerDeviceRouteRejectsADeviceOutsideTheUsersGroups() {
        perDeviceRoutes(HIDDEN).forEach((name, call) ->
                assertNotEquals(name + " must be rejected", Response.ResponseStatus.OK, call.get().getStatus()));
        assertEquals("no command, wake or sync may reach the hidden device", Collections.emptyList(), sideEffects);
    }

    @Test
    public void everyPerDeviceRouteServesADeviceInTheUsersGroups() {
        perDeviceRoutes(VISIBLE).forEach((name, call) ->
                assertEquals(name, Response.ResponseStatus.OK, call.get().getStatus()));
    }

    @Test
    public void aUserWithAllDevicesReachesEveryDeviceOfTheCustomer() {
        login(true);
        perDeviceRoutes(HIDDEN).forEach((name, call) ->
                assertEquals(name, Response.ResponseStatus.OK, call.get().getStatus()));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void bulkCommandsSkipDevicesOutsideTheUsersGroups() {
        AgentBulkCommandRequest req = new AgentBulkCommandRequest();
        req.setDeviceIds(Fakes.list(VISIBLE_ID, HIDDEN_ID));
        AgentCommand cmd = new AgentCommand();
        cmd.setType("device.lock");
        req.setCommand(cmd);

        Response r = resource.queueCommandBulk(req);

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, data.get("queued"));
        assertEquals(Collections.singletonList(HIDDEN_ID), data.get("skipped"));
        assertTrue(sideEffects.toString(), sideEffects.stream().noneMatch(s -> s.endsWith(HIDDEN)));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void syncSummaryCountsOnlyTheUsersDevices() {
        Response r = resource.getSyncSummary();
        List<ConfigSyncSummary> rows = (List<ConfigSyncSummary>) r.getData();
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).getTotal());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void syncSummaryCountsInSyncDevicesWithDeferredSettings() {
        ConfigSyncSummary s = ((List<ConfigSyncSummary>) resource.getSyncSummary().getData()).get(0);
        assertEquals(1, s.getInSync());
        assertEquals("in sync, but the latest config.apply deferred something", 1, s.getDeferred());
    }

    @Test
    public void creatingARolloutNeedsAccessToAllDevices() {
        RolloutResource.CreateRolloutRequest body = new RolloutResource.CreateRolloutRequest();
        body.setTargetVersion("2");
        body.setPackageName("com.example.agent");
        body.setApkVersionCode(2);
        body.setApkSha256(new String(new char[64]).replace('\0', 'a'));
        body.setCanaryDeviceNumbers(Fakes.list(VISIBLE));

        // A group-limited user would hold the customer's single active rollout
        assertNotEquals(Response.ResponseStatus.OK, rollouts.create(body).getStatus());
        assertEquals(Collections.emptyList(), sideEffects);

        login(true);
        assertEquals(Response.ResponseStatus.OK, rollouts.create(body).getStatus());
        assertTrue(sideEffects.toString(), sideEffects.contains("canary " + VISIBLE));
    }

    @Test
    public void aRolloutWithNoAccessibleCanaryIsNotCreated() {
        RolloutResource.CreateRolloutRequest body = new RolloutResource.CreateRolloutRequest();
        body.setTargetVersion("2");
        body.setPackageName("com.example.agent");
        body.setApkVersionCode(2);
        body.setApkSha256(new String(new char[64]).replace('\0', 'a'));
        body.setCanaryDeviceNumbers(Fakes.list("NOT-A-DEVICE"));

        login(true);
        assertNotEquals(Response.ResponseStatus.OK, rollouts.create(body).getStatus());
        assertEquals(Collections.emptyList(), sideEffects);
    }

    @Test
    public void cancellingTheFleetRolloutNeedsAccessToAllDevices() {
        assertNotEquals(Response.ResponseStatus.OK, rollouts.cancel(77).getStatus());
        assertEquals(Collections.emptyList(), sideEffects);

        login(true);
        assertEquals(Response.ResponseStatus.OK, rollouts.cancel(77).getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void activeRolloutProgressCountsOnlyTheUsersDevices() {
        active = true;   // a fleet-stage rollout over both devices
        Map<String, Object> progress = (Map<String, Object>) ((Map<String, Object>) rollouts.active().getData()).get("progress");
        assertEquals(1, ((com.hmdm.util.RolloutProgress.Counts) progress.get("fleet")).getTotal());

        login(true);
        progress = (Map<String, Object>) ((Map<String, Object>) rollouts.active().getData()).get("progress");
        assertEquals(2, ((com.hmdm.util.RolloutProgress.Counts) progress.get("fleet")).getTotal());
    }

    @Test
    public void promotingToTheWholeFleetNeedsAccessToAllDevices() {
        assertNotEquals(Response.ResponseStatus.OK, rollouts.promote(77).getStatus());
        assertEquals(Collections.emptyList(), sideEffects);

        login(true);
        assertEquals(Response.ResponseStatus.OK, rollouts.promote(77).getStatus());
    }
}
