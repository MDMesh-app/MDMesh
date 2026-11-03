package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.RolloutDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationVersion;
import com.hmdm.persistence.domain.Device;
import com.hmdm.util.AppReconcileDecision;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Configuration apps queued at check-in ({@link AppReconciler}) over fake DAOs: only what the device still needs,
 * nothing on the first sighting after an update, and nothing for an agent that can't install.
 */
public class AppReconcilerTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String HEX = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final Set<String> CAN_INSTALL = new HashSet<>(Collections.singletonList("app.silentInstall"));
    private static final long NOW = 1_000_000_000L;

    /** Configurations by id: their apps, as getPlainConfigurationApplications returns them for the customer. */
    private static final class FakeUnsecureDAO extends UnsecureDAO {
        final Map<Integer, List<Application>> configs = new HashMap<>();
        final Map<Integer, ApplicationVersion> versions = new HashMap<>();
        final List<Integer> customersAsked = new ArrayList<>();
        int appQueries;

        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "/nonexistent", 0, "com.mdmesh.agent");
        }

        List<Application> of(Integer id) {
            return configs.computeIfAbsent(id, k -> new ArrayList<>());
        }

        @Override public List<Application> getPlainConfigurationApplications(Integer customerId, Integer id) {
            appQueries++;
            customersAsked.add(customerId);
            return of(id);
        }
        /** Set to remove this version from the configuration right after its ids are read (a save racing in). */
        Integer removeAfterIdsRead;

        @Override public List<Integer> getConfigurationInstallVersionIds(Integer configurationId) {
            List<Integer> ids = new ArrayList<>();
            for (Application a : of(configurationId)) if (a.getAction() == 1) ids.add(a.getUsedVersionId());
            if (removeAfterIdsRead != null) {
                Integer v = removeAfterIdsRead;
                removeAfterIdsRead = null;
                of(configurationId).removeIf(a -> v.equals(a.getUsedVersionId()));
            }
            return ids;
        }
        @Override public ApplicationVersion findApplicationVersionById(Integer id) { return versions.get(id); }
    }

    /** In-memory agentCommand + device_state.appsRevision. */
    private static final class FakeCommandDAO extends AgentCommandDAO {
        final List<AgentCommand> commands = new ArrayList<>();
        String appsRevision;
        int historyReads;

        FakeCommandDAO() { super(null, null, null, null); }

        @Override public void insert(AgentCommand c) {
            c.setId(commands.size() + 1);
            commands.add(c);
        }
        @Override public void updateAppsRevision(String n, String revision) { appsRevision = revision; }
        @Override public List<AgentCommand> listLatestAppCommands(String n, int limit) {
            historyReads++;
            List<AgentCommand> out = new ArrayList<>();
            for (int i = commands.size() - 1; i >= 0 && out.size() < limit; i--) {
                if (n.equals(commands.get(i).getDeviceNumber())) out.add(commands.get(i));
            }
            return out;
        }
    }

    /** insertIfNotInFlight: the same payload pending or delivered is not queued twice. */
    private static final class FakeRolloutDAO extends RolloutDAO {
        final FakeCommandDAO commands;
        FakeRolloutDAO(FakeCommandDAO commands) { super(null); this.commands = commands; }
        @Override public boolean insertIfNotInFlight(AgentCommand cmd) {
            for (AgentCommand c : commands.commands) {
                if (c.getDeviceNumber().equals(cmd.getDeviceNumber()) && c.getPayload().equals(cmd.getPayload())
                        && ("pending".equals(c.getStatus()) || "delivered".equals(c.getStatus()))) return false;
            }
            commands.insert(cmd);
            return true;
        }
    }

    /** Hashes only this server's files, counting how often each is read. */
    private static final class FakeResolver extends ApkHashResolver {
        final List<String> asked = new ArrayList<>();
        FakeResolver() { super("/nonexistent", "http://mdm.lan:8080"); }
        @Override public String sha256Hex(String url) throws IOException {
            asked.add(url);
            if (!url.startsWith("http://mdm.lan:8080/files/")) throw new IOException(NOT_HOSTED);
            return HEX;
        }
    }

    private FakeUnsecureDAO dao;
    private FakeCommandDAO commands;
    private AppReconciler reconciler;
    private Device device;

    @Before
    public void setUp() {
        dao = new FakeUnsecureDAO();
        commands = new FakeCommandDAO();
        ConfigAppInstaller installer = new ConfigAppInstaller(dao, commands, new AgentWakeHub(null) {
            @Override public void wake(String deviceNumber, String wakeKind) { }
        }, new FakeResolver());
        reconciler = new AppReconciler(dao, commands, new FakeRolloutDAO(commands), installer);
        device = new Device();
        device.setNumber("d1");
        device.setCustomerId(7);
        device.setConfigurationId(5);
    }

    private Application configApp(int configurationId, int versionId, String pkg, int versionCode, String url) {
        Application a = new Application();
        a.setUrl(url);
        a.setPkg(pkg);
        a.setVersionCode(versionCode);
        a.setAction(1);
        a.setUsedVersionId(versionId);
        ApplicationVersion v = new ApplicationVersion();
        v.setId(versionId);
        v.setUrl(url);
        dao.versions.put(versionId, v);
        dao.of(configurationId).add(a);
        return a;
    }

    private Application hosted(int configurationId, int versionId, String pkg, int versionCode) {
        return configApp(configurationId, versionId, pkg, versionCode, "http://mdm.lan:8080/files/" + pkg + ".apk");
    }

    private int reconcile() {
        return reconciler.reconcile(device, CAN_INSTALL, commands.appsRevision, NOW);
    }

    private String currentRevision(int configurationId) {
        return AppReconcileDecision.revision(configurationId, dao.getConfigurationInstallVersionIds(configurationId));
    }

    private String pkgOf(AgentCommand c) throws Exception {
        return M.readTree(c.getPayload()).get("packageName").asText();
    }

    /** The agent reports every open install as done. */
    private void agentInstallsEverything() {
        for (AgentCommand c : commands.commands) {
            if ("pending".equals(c.getStatus()) || "delivered".equals(c.getStatus())) {
                c.setStatus("done");
                c.setDeliveredAt(NOW);
                c.setCompletedAt(NOW);
            }
        }
    }

    /** A device converged on configuration 5's apps a and b (enrolled, installed, revision recorded). */
    private void converged() {
        hosted(5, 10, "com.a", 1);
        hosted(5, 11, "com.b", 1);
        for (Application a : dao.of(5)) commands.insert(new ConfigAppInstaller(dao, commands, null, new FakeResolver())
                .installCommand(device, a, NOW));
        agentInstallsEverything();
        commands.appsRevision = currentRevision(5);
    }

    @Test
    public void a_device_enrolled_after_the_update_has_its_whole_set_decided_without_duplicates() {
        hosted(5, 10, "com.a", 1);
        hosted(5, 11, "com.b", 1);
        // Enrollment queued com.a; com.b was added to the configuration before the first check-in.
        commands.insert(new ConfigAppInstaller(dao, commands, null, new FakeResolver()).installCommand(device,
                dao.of(5).get(0), NOW));
        assertNull(commands.appsRevision);
        assertEquals(1, reconcile());
        assertEquals(2, commands.commands.size());
        assertNull("com.a still in flight: not stored yet", commands.appsRevision);
        agentInstallsEverything();
        assertEquals(0, reconcile());
        assertEquals(2, commands.commands.size());
        assertEquals(currentRevision(5), commands.appsRevision);
    }

    @Test
    public void an_unchanged_configuration_costs_one_select() {
        converged();
        int before = commands.commands.size();
        assertEquals(0, reconcile());
        assertEquals(before, commands.commands.size());
        assertEquals(0, dao.appQueries);
        assertEquals(0, commands.historyReads);
    }

    @Test
    public void an_app_added_to_the_configuration_is_queued_once() throws Exception {
        converged();
        int before = commands.commands.size();
        hosted(5, 12, "com.c", 3);

        assertEquals(1, reconcile());
        assertEquals(before + 1, commands.commands.size());
        AgentCommand c = commands.commands.get(before);
        assertEquals("com.c", pkgOf(c));
        assertEquals("pending", c.getStatus());
        assertEquals("app.silentInstall", c.getRequiresCapability());
        assertEquals(HEX, M.readTree(c.getPayload()).get("sha256").asText());
        assertTrue(c.getPayload().contains("\"versionCode\":3"));

        // Still in flight on the next check-ins: not queued again, and the revision waits for the result.
        assertEquals(0, reconcile());
        assertEquals(0, reconcile());
        assertEquals(before + 1, commands.commands.size());
        agentInstallsEverything();
        assertEquals(0, reconcile());
        assertEquals(currentRevision(5), commands.appsRevision);
    }

    @Test
    public void a_device_offline_during_the_update_gets_an_app_added_after_it() throws Exception {
        // a and b were on the configuration at the update, which stored that set (no command history for them).
        hosted(5, 10, "com.a", 1);
        hosted(5, 11, "com.b", 1);
        commands.appsRevision = currentRevision(5);
        // The device is offline while c is added; at its next check-in it gets c, and only c.
        hosted(5, 12, "com.c", 1);
        assertEquals(1, reconcile());
        assertEquals(1, commands.commands.size());
        assertEquals("com.c", pkgOf(commands.commands.get(0)));
    }

    @Test
    public void a_set_changed_by_a_save_racing_the_check_in_is_not_stored() {
        converged();
        String reconciled = commands.appsRevision;
        hosted(5, 12, "com.c", 3);
        dao.removeAfterIdsRead = 12;   // c removed again before the app list is read: c was never decided
        assertEquals(0, reconcile());
        assertEquals(reconciled, commands.appsRevision);
        hosted(5, 12, "com.c", 3);     // added back later: decided then
        assertEquals(1, reconcile());
    }

    @Test
    public void a_new_version_in_the_configuration_is_installed() throws Exception {
        converged();
        int before = commands.commands.size();
        Application b = dao.of(5).get(1);
        b.setUsedVersionId(21);
        b.setVersionCode(2);
        assertEquals(1, reconcile());
        assertEquals("com.b", pkgOf(commands.commands.get(before)));
        assertTrue(commands.commands.get(before).getPayload().contains("\"versionCode\":2"));
    }

    @Test
    public void an_app_removed_from_the_configuration_queues_nothing() {
        converged();
        int before = commands.commands.size();
        dao.of(5).remove(1);
        assertEquals(0, reconcile());
        assertEquals(before, commands.commands.size());
        assertEquals(currentRevision(5), commands.appsRevision);
        assertEquals("no app list or history for a removal", 0, dao.appQueries + commands.historyReads);
    }

    @Test
    public void a_device_moved_to_another_configuration_gets_the_apps_it_lacks() throws Exception {
        converged();
        hosted(5, 40, "com.q", 1);   // in the old set when the update stored it, never installed
        commands.appsRevision = currentRevision(5);
        int before = commands.commands.size();
        hosted(8, 10, "com.a", 1);   // already installed
        hosted(8, 40, "com.q", 1);
        hosted(8, 30, "com.x", 1);
        device.setConfigurationId(8);

        // The new configuration's whole set is decided, not just what it adds over the old one.
        assertEquals(2, reconcile());
        assertEquals("com.q", pkgOf(commands.commands.get(before)));
        assertEquals("com.x", pkgOf(commands.commands.get(before + 1)));
        assertEquals(Collections.singletonList(7), new ArrayList<>(new HashSet<>(dao.customersAsked)));
    }

    @Test
    public void a_failing_install_backs_off_then_gives_up_and_settles() {
        converged();
        hosted(5, 12, "com.c", 3);
        long t = NOW;
        for (int attempt = 1; attempt <= AppReconcileDecision.MAX_ATTEMPTS; attempt++) {
            assertEquals("attempt " + attempt, 1, reconciler.reconcile(device, CAN_INSTALL, commands.appsRevision, t));
            AgentCommand last = commands.commands.get(commands.commands.size() - 1);
            last.setStatus("failed");
            last.setDeliveredAt(t);
            last.setCompletedAt(t + 1000);
            // Every check-in inside the backoff window queues nothing.
            assertEquals(0, reconciler.reconcile(device, CAN_INSTALL, commands.appsRevision, t + 2000));
            t += AppReconcileDecision.backoffMs(attempt) + 2000;
        }
        int total = commands.commands.size();
        assertEquals(0, reconciler.reconcile(device, CAN_INSTALL, commands.appsRevision, t + 24L * 3600_000L));
        assertEquals(total, commands.commands.size());
        assertEquals("given up: settled, so later check-ins are cheap again", currentRevision(5), commands.appsRevision);
    }

    @Test
    public void an_apk_without_a_hash_is_recorded_once_as_failed() {
        converged();
        String reconciled = commands.appsRevision;
        int before = commands.commands.size();
        configApp(5, 12, "com.ext", 1, "https://apks.example.com/ext.apk");
        assertEquals(0, reconcile());
        assertEquals(before + 1, commands.commands.size());
        assertEquals("failed", commands.commands.get(before).getStatus());
        assertEquals(currentRevision(5), commands.appsRevision);
        commands.appsRevision = reconciled;   // even if the change is looked at again
        assertEquals(0, reconcile());
        assertEquals(before + 1, commands.commands.size());
    }

    @Test
    public void an_agent_without_silent_install_gets_nothing() {
        converged();
        hosted(5, 12, "com.c", 3);
        int before = commands.commands.size();
        assertEquals(0, reconciler.reconcile(device, Collections.<String>emptySet(), commands.appsRevision, NOW));
        assertEquals(before, commands.commands.size());
        assertEquals(0, dao.appQueries);
    }

    @Test
    public void a_device_without_a_configuration_gets_nothing() {
        device.setConfigurationId(null);
        assertEquals(0, reconcile());
        assertNull(commands.appsRevision);
    }

    @Test
    public void a_failure_is_swallowed_and_the_revision_kept() {
        converged();
        String before = commands.appsRevision;
        hosted(5, 12, "com.c", 3);
        AppReconciler broken = new AppReconciler(dao, commands, null, new ConfigAppInstaller(dao, commands, null,
                new FakeResolver()));
        assertEquals(0, broken.reconcile(device, CAN_INSTALL, commands.appsRevision, NOW));
        assertEquals("not settled: the next check-in tries again", before, commands.appsRevision);
    }
}
