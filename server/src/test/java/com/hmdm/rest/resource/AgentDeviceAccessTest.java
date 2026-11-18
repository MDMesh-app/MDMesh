package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.hmdm.extension.ConfigurationExtensionValidator;
import com.hmdm.extension.DesiredConfigContributor;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.RolloutDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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
    private boolean activeCanary;
    private String rolloutDeviceVersion = "1";
    private String rolloutDeviceCaps = "{\"appManagement\":[\"silentInstall\"]}";
    /** Non-null: another request moved the rollout to this stage right after the resource first read it. */
    private String racedStage;
    private int activeReads;
    private boolean socketOpen;
    private FakeMapper<DeviceMapper> deviceMapper;
    private AgentAdminResource resource;
    private RolloutResource rollouts;
    private Map<Class<?>, Object> deps;
    private int configurationReads;
    private final Set<String> wiped = new HashSet<>();
    private String appliedRevision = "rev";
    private int revisionReads;
    private final List<String> enrolledQuery = new ArrayList<>();

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
                configurationReads++;
                if (id == null || (id != OWN_CONFIG && id != OTHER_OWN_CONFIG)) return null;
                Configuration c = new Configuration();
                c.setId(id);
                c.setCustomerId(CUSTOMER);
                // Kiosk exit PINs saved before the 6-to-12-digit rule: the devices' own, and another configuration's.
                c.setPassword(id == OWN_CONFIG ? "4931" : "7777");
                return c;
            }
            @Override public List<com.hmdm.persistence.domain.ManagedAppPermission> getManagedAppPermissions(Integer id) {
                return Collections.emptyList();
            }
            @Override public List<Application> getPlainConfigurationAppsOptimized(Integer id) { return new ArrayList<>(); }
        };
        AgentCommandDAO commandDAO = new AgentCommandDAO(null, null, null, null) {
            @Override public void insert(AgentCommand c) {
                inserted.add(c);
                sideEffects.add("insert " + c.getDeviceNumber());
            }
            @Override public DeviceState getState(String n) { return new DeviceState(); }
            @Override public List listEvents(String n, long s, int l) { return new ArrayList<>(); }
            @Override public List<AgentCommand> listHistory(String n, long s, int l) { return new ArrayList<>(); }
            @Override public List listLocations(String n, long s, int l) { return new ArrayList<>(); }
            @Override public String getDeviceCapabilities(String n) { return null; }
            @Override public AgentCommand findLatestOfType(String n, String t) { return null; }
            @Override public List<Device> listEnrolledSince(int customerId, int userId, boolean superAdmin, long since,
                                                            int limit) {
                enrolledQuery.add(customerId + ":" + userId + ":" + since + ":" + limit);
                List<Device> rows = new ArrayList<>();
                for (Device d : devices.values()) {
                    if (!allDevices && d.getId() != VISIBLE_ID) continue; // what the query's access filter does
                    Device copy = device(d.getId(), d.getNumber());
                    copy.setEnrollTime(since + 1);
                    copy.setLastUpdate(since + 1);
                    rows.add(copy);
                }
                return rows;
            }
            @Override public List<DeviceSyncRow> listDevicesForSync(int customerId) {
                List<DeviceSyncRow> rows = new ArrayList<>();
                for (String n : Arrays.asList(VISIBLE, HIDDEN)) {
                    DeviceSyncRow r = new DeviceSyncRow();
                    r.setDeviceNumber(n);
                    r.setConfigurationId(5);
                    r.setCapabilitiesJson("{\"device\":[\"configApply\"]}");
                    r.setAppliedConfigRevision(appliedRevision);
                    r.setLastApplyDeferred(true);
                    r.setWiped(wiped.contains(n));
                    rows.add(r);
                }
                return rows;
            }
        };
        AgentWakeHub wakeHub = new AgentWakeHub(commandDAO) {
            @Override public void wake(String n, String kind) { sideEffects.add("wake " + n); }
            @Override public boolean isOnline(String n) { return socketOpen; }
        };
        ConfigAppInstaller installer = new ConfigAppInstaller(unsecureDAO, commandDAO, wakeHub, null) {
            @Override public int enqueueConfigApps(Device d) { sideEffects.add("syncApps " + d.getNumber()); return 1; }
        };
        ConfigReconciler reconciler = new ConfigReconciler(unsecureDAO, commandDAO, Fakes.noExtensionPoints()) {
            @Override public com.hmdm.rest.json.agent.DesiredConfig statusDocument(Device d) {
                revisionReads++;
                com.hmdm.rest.json.agent.DesiredConfig doc = new com.hmdm.rest.json.agent.DesiredConfig();
                doc.setRevision("rev");
                return doc;
            }
        };

        RolloutDAO rolloutDAO = new RolloutDAO(null) {
            @Override public AgentRollout findActiveByCustomer(int c) {
                if (!active) return null;
                AgentRollout r = findById(77);
                String stage = activeCanary ? "canary" : "fleet";
                if (racedStage != null && activeReads++ > 0) stage = racedStage;
                if (!"canary".equals(stage) && !"fleet".equals(stage)) return null;
                r.setStage(stage);
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
            @Override public boolean moveStage(int id, String from, String to) {
                if (racedStage != null && !racedStage.equals(from)) return false;
                sideEffects.add("stage " + to);
                return true;
            }
            @Override public boolean cancelActive(int id) {
                if ("done".equals(racedStage) || "cancelled".equals(racedStage)) return false;
                sideEffects.add("stage cancelled");
                return true;
            }
            @Override public List<String> listCanaryNumbers(int id) {
                return activeCanary ? Arrays.asList(VISIBLE, HIDDEN) : new ArrayList<String>();
            }
            @Override public List<RolloutDeviceRow> listCustomerDevices(int c) {
                List<RolloutDeviceRow> rows = new ArrayList<>();
                for (String n : Arrays.asList(VISIBLE, HIDDEN)) {
                    RolloutDeviceRow r = new RolloutDeviceRow();
                    r.setDeviceNumber(n);
                    r.setAgentVersion(rolloutDeviceVersion);
                    r.setCapabilitiesJson(rolloutDeviceCaps);
                    rows.add(r);
                }
                return rows;
            }
            @Override public Set<String> listPendingInstallNumbers(int c, String p) { return new HashSet<>(); }
        };
        RolloutRequeuer requeuer = new RolloutRequeuer(rolloutDAO) {
            @Override public boolean enqueue(AgentRollout r, String n, long now) { sideEffects.add("rollout " + n); return true; }
        };

        deps = new HashMap<>();
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
        routes.put("retryConfig", () -> resource.retryConfig(number));
        return routes;
    }

    @Test
    public void forceSyncSaysWhetherTheWakeCanReachTheDevice() {
        for (boolean open : new boolean[]{true, false}) {
            socketOpen = open;
            Response r = resource.forceSync(VISIBLE);
            assertEquals(Response.ResponseStatus.OK, r.getStatus());
            assertEquals(Collections.singletonMap("connected", open), r.getData());
        }
        assertEquals("the wake goes out either way", Arrays.asList("wake " + VISIBLE, "wake " + VISIBLE), sideEffects);
    }

    private final List<AgentCommand> inserted = new ArrayList<>();

    private static AgentCommand kioskEnter(String payload) {
        AgentCommand cmd = new AgentCommand();
        cmd.setType("kiosk.enter");
        cmd.setPayload(payload);
        return cmd;
    }

    private Response bulk(AgentCommand cmd, Integer... ids) {
        AgentBulkCommandRequest req = new AgentBulkCommandRequest();
        req.setDeviceIds(Fakes.list(ids));
        req.setCommand(cmd);
        return resource.queueCommandBulk(req);
    }

    @Test
    public void aKioskEnterWithAPinOutsideTheRuleIsRefusedBeforeAnythingIsQueued() {
        // Every short PIN is refused alike, a configuration's included: the answer says nothing about stored PINs.
        for (String pin : new String[]{"1234", "4931", "7777", "letmeout"}) {
            AgentCommand cmd = kioskEnter("{\"mode\":\"launcher\",\"password\":\"" + pin + "\"}");
            Response one = resource.queueCommand(VISIBLE, cmd);
            assertEquals(pin, Response.ResponseStatus.ERROR, one.getStatus());
            assertEquals(com.hmdm.util.KioskExitPin.FORMAT_MESSAGE, one.getMessage());
            assertEquals(com.hmdm.util.KioskExitPin.FORMAT_MESSAGE, bulk(cmd, VISIBLE_ID).getMessage());
        }
        assertEquals("nothing queued or woken", Collections.emptyList(), sideEffects);

        for (String pin : new String[]{"\"482139\"", "\"\"", "null"}) {
            AgentCommand cmd = kioskEnter("{\"mode\":\"launcher\",\"password\":" + pin + "}");
            assertEquals(pin, Response.ResponseStatus.OK, resource.queueCommand(VISIBLE, cmd).getStatus());
        }
    }

    @Test
    public void returnToConfiguredKioskGetsEachDevicesOwnConfigurationPinFromTheServer() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        // The console sends no PIN; a PIN a client puts in anyway is replaced, never checked or passed on.
        AgentCommand cmd = kioskEnter("{\"mode\":\"launcher\",\"configuredPin\":true,\"password\":\"7777\"}");
        assertEquals(Response.ResponseStatus.OK, resource.queueCommand(VISIBLE, cmd).getStatus());
        login(true);
        assertEquals(Response.ResponseStatus.OK, bulk(cmd, VISIBLE_ID, HIDDEN_ID).getStatus());
        assertEquals(3, inserted.size());
        for (AgentCommand queued : inserted) {
            com.fasterxml.jackson.databind.JsonNode p = json.readTree(queued.getPayload());
            assertEquals("the legacy PIN of the device's own configuration", "4931", p.get("password").asText());
            assertNull("the request key doesn't reach the agent", p.get("configuredPin"));
            assertEquals("launcher", p.get("mode").asText());
        }
    }

    @Test
    public void returnToConfiguredKioskOnADeviceWithoutAConfigurationIsRefusedOrSkipped() {
        devices.get(VISIBLE).setConfigurationId(null);
        AgentCommand cmd = kioskEnter("{\"mode\":\"launcher\",\"configuredPin\":true}");
        assertEquals("error.agent.command.noConfiguration", resource.queueCommand(VISIBLE, cmd).getMessage());
        login(true);
        Response r = bulk(cmd, VISIBLE_ID, HIDDEN_ID);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.getData();
        assertEquals(1, result.get("queued"));
        assertEquals(Fakes.list(VISIBLE_ID), result.get("skipped"));
    }

    @Test
    public void retryConfigOnAnAgentWithoutConfigurationsQueuesAndWakesNothing() {
        socketOpen = true;
        Response r = resource.retryConfig(VISIBLE);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("result", "unsupported");
        expected.put("connected", true);
        assertEquals(expected, r.getData());
        assertEquals(Collections.emptyList(), sideEffects);
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
    public void everyRouteThatQueuesForADeviceRefusesAWipedOne() {
        // The console greys these out for a wiped device, but a page opened before the wipe, or the API, still asks.
        devices.get(VISIBLE).setWipedAt(1000L);
        socketOpen = true;
        Map<String, Supplier<Response>> routes = perDeviceRoutes(VISIBLE);
        for (String name : new String[]{"queueCommand", "syncApps", "retryConfig"}) {
            Response r = routes.get(name).get();
            assertEquals(name, Response.ResponseStatus.ERROR, r.getStatus());
            assertEquals(name, "error.agent.command.wiped", r.getMessage());
        }
        assertEquals("nothing queued or woken", Collections.emptyList(), sideEffects);
        assertEquals("its history and state still read", Response.ResponseStatus.OK, routes.get("commands").get().getStatus());
    }

    @Test
    public void everyRouteThatQueuesForADeviceAnswersAnUnknownOneAlike() {
        Map<String, Supplier<Response>> routes = perDeviceRoutes("NO-SUCH-DEVICE");
        for (String name : new String[]{"queueCommand", "syncApps", "retryConfig", "forceSync"}) {
            assertEquals(name, "error.agent.device.unknown", routes.get(name).get().getMessage());
        }
        assertEquals(Collections.emptyList(), sideEffects);
    }

    @Test
    public void aWipedDeviceTheUserCannotSeeIsRefusedForAccessNotAsWiped() {
        devices.get(HIDDEN).setWipedAt(1000L);
        AgentCommand cmd = new AgentCommand();
        cmd.setType("device.lock");
        Response r = resource.queueCommand(HIDDEN, cmd);
        assertEquals("not a way to learn a hidden device was wiped", "error.permission.denied", r.getMessage());
    }

    @Test
    public void aDeviceNoLongerMarkedWipedTakesCommandsAgain() {
        // The mark is lifted when the wipe reports failed or the device checks in after it (AgentResource).
        devices.get(VISIBLE).setWipedAt(1000L);
        devices.get(VISIBLE).setWipedAt(null);
        AgentCommand cmd = new AgentCommand();
        cmd.setType("device.lock");
        assertEquals(Response.ResponseStatus.OK, resource.queueCommand(VISIBLE, cmd).getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void bulkCommandsSkipWipedDevices() {
        login(true);
        devices.get(HIDDEN).setWipedAt(1000L);
        AgentCommand cmd = new AgentCommand();
        cmd.setType("device.lock");

        Response r = bulk(cmd, VISIBLE_ID, HIDDEN_ID);

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, data.get("queued"));
        assertEquals(Collections.singletonList(HIDDEN_ID), data.get("skipped"));
        assertEquals(Arrays.asList("insert " + VISIBLE, "wake " + VISIBLE), sideEffects);
    }

    @Test
    public void destructiveCommandsAreNeverQueuedInBulk() {
        login(true);
        for (String type : new String[]{"device.wipe", "device.passcodeReset", "app.uninstall", " App.Uninstall ",
                "app.setHidden"}) {
            AgentCommand cmd = new AgentCommand();
            cmd.setType(type);
            cmd.setPayload("{\"packageName\":\"com.example.pos\"}");
            Response r = bulk(cmd, VISIBLE_ID, HIDDEN_ID);
            assertEquals(type, Response.ResponseStatus.ERROR, r.getStatus());
            assertEquals(type, "error.agent.command.bulkForbidden", r.getMessage());
        }
        assertEquals("nothing queued or woken", Collections.emptyList(), sideEffects);

        // One device at a time, from its page, they still go through.
        AgentCommand uninstall = new AgentCommand();
        uninstall.setType("app.uninstall");
        uninstall.setPayload("{\"packageName\":\"com.example.pos\"}");
        assertEquals(Response.ResponseStatus.OK, resource.queueCommand(VISIBLE, uninstall).getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theConsolesBulkActionsStillGoOut() {
        login(true);
        for (String type : new String[]{"device.lock", "device.reboot", "device.ring", "device.ringStop",
                "device.alert", "device.lockscreenMessage", "device.powerMode", "device.locationMode", "kiosk.enter",
                "kiosk.exit", "kiosk.clearPinLockout", "apps.scan"}) {
            AgentCommand cmd = new AgentCommand();
            cmd.setType(type);
            Response r = bulk(cmd, VISIBLE_ID, HIDDEN_ID);
            assertEquals(type, Response.ResponseStatus.OK, r.getStatus());
            assertEquals(type, 2, ((Map<String, Object>) r.getData()).get("queued"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void devicesEnrolledSinceListOnlyTheUsersDevicesWithABoundedQuery() {
        Response r = resource.listEnrolledSince(1000L, 500);

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        Map<String, Object> body = (Map<String, Object>) r.getData();
        assertTrue("with the server's time", body.get("serverTime") instanceof Long);
        List<com.hmdm.rest.json.agent.EnrolledDeviceView> rows =
                (List<com.hmdm.rest.json.agent.EnrolledDeviceView>) body.get("devices");
        assertEquals(1, rows.size());
        assertEquals(VISIBLE, rows.get(0).getNumber());
        assertEquals(Long.valueOf(1001L), rows.get(0).getEnrollTime());
        assertEquals("the customer's, for this user, since then, at most 50", "1:9:1000:50", enrolledQuery.get(0));
    }

    @Test
    public void devicesEnrolledSinceIsAnIndexedQuery() {
        String sql = Fakes.sqlOf(com.hmdm.persistence.mapper.AgentDeviceMapper.class, "listEnrolledSince")
                .replaceAll("\\s+", " ").toLowerCase();
        String where = Fakes.whereOf(sql);
        assertTrue(where, where.contains("devices.customerid = #{customerid}"));
        assertTrue(where, where.contains("devices.enrolltime >= #{since}"));
        assertTrue("access filtered before the limit: " + where, where.contains("users.alldevicesavailable = true"));
        assertTrue(sql, sql.contains("order by devices.enrolltime desc limit #{limit}"));
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
    @SuppressWarnings("unchecked")
    public void syncSummaryKeepsAWipedDeviceOutOfTheCountsThatAddUpToTotal() {
        login(true);
        wiped.add(HIDDEN);
        appliedRevision = "old";
        ConfigSyncSummary s = ((List<ConfigSyncSummary>) resource.getSyncSummary().getData()).get(0);
        assertEquals("the live device only", 1, s.getTotal());
        assertEquals(1, s.getOutOfSync());
        assertEquals(1, s.getInSync() + s.getOutOfSync() + s.getUnsupported() + s.getNeverSeen());
        assertEquals(1, s.getWiped());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void syncSummaryStillListsAConfigurationWithOnlyWipedDevices() {
        wiped.add(VISIBLE);
        List<ConfigSyncSummary> rows = (List<ConfigSyncSummary>) resource.getSyncSummary().getData();
        assertEquals("its delete would be refused, so the console has to hear about it", 1, rows.size());
        ConfigSyncSummary s = rows.get(0);
        assertEquals(5, s.getConfigurationId());
        assertEquals(1, s.getWiped());
        assertEquals(0, s.getTotal());
        assertEquals(0, s.getInSync() + s.getOutOfSync() + s.getUnsupported() + s.getNeverSeen());
        assertEquals("nothing to compare a wiped device with", 0, revisionReads);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void syncSummaryDoesNotCountAWipedDeviceTheUserCannotSee() {
        wiped.add(HIDDEN);
        ConfigSyncSummary s = ((List<ConfigSyncSummary>) resource.getSyncSummary().getData()).get(0);
        assertEquals(0, s.getWiped());
        assertEquals(1, s.getTotal());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void syncSummaryCountsADeviceNormallyOnceItIsNoLongerWiped() {
        wiped.add(VISIBLE);
        resource.getSyncSummary();
        wiped.clear();
        appliedRevision = "old";
        ConfigSyncSummary s = ((List<ConfigSyncSummary>) resource.getSyncSummary().getData()).get(0);
        assertEquals(0, s.getWiped());
        assertEquals(1, s.getTotal());
        assertEquals("true until it re-applies", 1, s.getOutOfSync());
    }

    /**
     * A configuration whose plugin contributor fails has no current revision (null). The summary still works it out
     * once per configuration per poll, not once per device: the failure, and its stack trace, aren't repeated for
     * every device on it.
     */
    @Test
    public void syncSummaryWorksOutAFailingConfigurationsRevisionOncePerPoll() throws Exception {
        final int[] runs = {0};
        DesiredConfigContributor broken = new DesiredConfigContributor() {
            @Override public String pluginId() { return "broken"; }
            @Override public Map<String, Boolean> policies(Configuration c, List<Application> apps) {
                runs[0]++;
                throw new IllegalArgumentException("broken plugin");
            }
        };
        deps.put(ConfigReconciler.class, new ConfigReconciler((UnsecureDAO) deps.get(UnsecureDAO.class),
                (AgentCommandDAO) deps.get(AgentCommandDAO.class),
                Fakes.extensionPoints(Collections.<ConfigurationExtensionValidator>emptySet(), Collections.singleton(broken))));
        resource = construct(AgentAdminResource.class, deps);
        login(true);
        configurationReads = 0;

        @SuppressWarnings("unchecked")
        List<ConfigSyncSummary> rows = (List<ConfigSyncSummary>) resource.getSyncSummary().getData();
        assertEquals(2, rows.get(0).getTotal());
        assertEquals("both devices are out of sync", 2, rows.get(0).getOutOfSync());
        assertEquals(1, runs[0]);
        assertEquals(1, configurationReads);
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
    @SuppressWarnings("unchecked")
    public void rolloutTargetSummaryReportsWhetherEveryEligibleDeviceIsCurrent() {
        login(true);
        Map<String, Object> pending = (Map<String, Object>) rollouts.summary("2").getData();
        assertFalse((Boolean) pending.get("complete"));
        assertEquals(2, ((com.hmdm.util.RolloutProgress.Counts) pending.get("progress")).getOutstanding());

        rolloutDeviceVersion = "2";
        Map<String, Object> current = (Map<String, Object>) rollouts.summary("2").getData();
        assertTrue((Boolean) current.get("complete"));
        assertEquals(2, ((com.hmdm.util.RolloutProgress.Counts) current.get("progress")).getUpdated());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void settledCanaryCoveringTheWholeFleetCompletesDuringStatusRefresh() {
        login(true);
        active = true;
        activeCanary = true;
        rolloutDeviceVersion = "2";

        Map<String, Object> view = (Map<String, Object>) rollouts.active().getData();

        assertEquals("done", view.get("stage"));
        assertTrue(sideEffects.toString(), sideEffects.contains("stage done"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aWholeFleetCanaryOfDevicesThatCannotUpdateThemselvesStaysBlocked() {
        login(true);
        active = true;
        activeCanary = true;
        rolloutDeviceCaps = "{}";

        Map<String, Object> view = (Map<String, Object>) rollouts.active().getData();

        assertEquals("canary", view.get("stage"));
        assertFalse(sideEffects.toString(), sideEffects.contains("stage done"));
        assertFalse((Boolean) ((Map<String, Object>) rollouts.summary("2").getData()).get("complete"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void completingTheCanaryNeverOverwritesACancelThatGotThereFirst() {
        login(true);
        active = true;
        activeCanary = true;
        rolloutDeviceVersion = "2";
        racedStage = "cancelled";

        assertNull(rollouts.active().getData());
        assertFalse(sideEffects.toString(), sideEffects.contains("stage done"));
    }

    @Test
    public void promoteCannotReopenARolloutThatFinishedMeanwhile() {
        login(true);
        racedStage = "done";

        assertEquals("error.rollout.stage", rollouts.promote(77).getMessage());
        assertEquals(Collections.emptyList(), sideEffects);
    }

    @Test
    public void cancelLeavesAFinishedRolloutDone() {
        login(true);
        racedStage = "done";

        assertEquals(Response.ResponseStatus.OK, rollouts.cancel(77).getStatus());
        assertEquals(Collections.emptyList(), sideEffects);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theViewSaysHowManyDevicesAPromoteWouldReach() {
        login(true);
        active = true;
        activeCanary = true; // both devices are canaries
        Map<String, Object> progress = (Map<String, Object>) ((Map<String, Object>) rollouts.active().getData()).get("progress");
        assertEquals(0, progress.get("others"));

        activeCanary = false;
        progress = (Map<String, Object>) ((Map<String, Object>) rollouts.active().getData()).get("progress");
        assertEquals(2, progress.get("others"));
    }

    @Test
    public void promotingToTheWholeFleetNeedsAccessToAllDevices() {
        assertNotEquals(Response.ResponseStatus.OK, rollouts.promote(77).getStatus());
        assertEquals(Collections.emptyList(), sideEffects);

        login(true);
        assertEquals(Response.ResponseStatus.OK, rollouts.promote(77).getStatus());
    }
}
