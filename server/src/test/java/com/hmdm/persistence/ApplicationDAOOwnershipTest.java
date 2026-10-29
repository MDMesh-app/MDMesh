package com.hmdm.persistence;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationVersion;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.ApplicationMapper;
import com.hmdm.rest.json.ApplicationConfigurationLink;
import com.hmdm.rest.json.ApplicationVersionConfigurationLink;
import com.hmdm.rest.json.LinkConfigurationsToAppRequest;
import com.hmdm.rest.json.LinkConfigurationsToAppVersionRequest;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.Call;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Application, version and configuration-link mutations act only on rows the caller owns, whatever ids and
 * ownership fields the request carries.
 */
public class ApplicationDAOOwnershipTest {

    private static final int CUSTOMER = 1;
    private static final int OTHER_CUSTOMER = 2;
    private static final int MASTER = 3;

    private static final int OWN_APP = 100, OTHER_APP = 200, COMMON_APP = 300;
    private static final int OWN_VERSION = 1000, OTHER_VERSION = 2000, COMMON_VERSION = 3000;
    private static final int OWN_CONFIG = 5, OWN_CONFIG_2 = 6, OTHER_CONFIG = 7;

    private FakeMapper<ApplicationMapper> fake;
    private ApplicationDAO dao;

    private static final Map<Integer, Integer> APP_CUSTOMER = new HashMap<>();
    private static final Map<Integer, Integer> VERSION_APP = new HashMap<>();
    static {
        APP_CUSTOMER.put(OWN_APP, CUSTOMER);
        APP_CUSTOMER.put(OTHER_APP, OTHER_CUSTOMER);
        APP_CUSTOMER.put(COMMON_APP, MASTER);
        VERSION_APP.put(OWN_VERSION, OWN_APP);
        VERSION_APP.put(OTHER_VERSION, OTHER_APP);
        VERSION_APP.put(COMMON_VERSION, COMMON_APP);
        APP_CUSTOMER.put(101, CUSTOMER);
        VERSION_APP.put(1010, 101);
    }

    private static final int OWN_APP_2_VERSION = 1010;

    private static Application dbApp(int id) {
        if (!APP_CUSTOMER.containsKey(id)) return null;
        Application a = new Application();
        a.setId(id);
        a.setPkg("pkg" + id);
        a.setName("app" + id);
        a.setCustomerId(APP_CUSTOMER.get(id));
        a.setCommonApplication(APP_CUSTOMER.get(id) == MASTER);
        a.setLatestVersion(id * 10);
        return a;
    }

    private static ApplicationVersion dbVersion(int id) {
        if (!VERSION_APP.containsKey(id)) return null;
        ApplicationVersion v = new ApplicationVersion();
        v.setId(id);
        v.setApplicationId(VERSION_APP.get(id));
        v.setVersion("1");
        v.setUrl("https://example.com/" + id + ".apk");
        v.setCommonApplication(APP_CUSTOMER.get(VERSION_APP.get(id)) == MASTER);
        return v;
    }

    @Before
    public void setUp() {
        fake = new FakeMapper<>(ApplicationMapper.class)
                .answer("findById", a -> dbApp((Integer) a[0]))
                .answer("findVersionById", a -> dbVersion((Integer) a[0]))
                .answer("getCustomerConfigurationIds", a -> {
                    int customerId = (Integer) a[0];
                    if (customerId == CUSTOMER) return Fakes.list(OWN_CONFIG, OWN_CONFIG_2);
                    if (customerId == OTHER_CUSTOMER) return Fakes.list(OTHER_CONFIG);
                    return Fakes.list();
                });
        dao = new ApplicationDAO(fake.mapper, null, "files", "https://mdm.example.com", "", null);
        login(Fakes.user(5, CUSTOMER, "edit_applications", "edit_application_versions"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static void login(User u) {
        SecurityContext.release();
        SecurityContext.init(u);
    }

    private void assertNoMutation() {
        assertTrue("no row may change: " + fake.calls,
                fake.callsStartingWith("update", "delete", "insert", "remove", "uninstall").isEmpty());
    }

    private static void expectDenied(Runnable r) {
        try {
            r.run();
            fail("expected a SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
    }

    // ---- applications ------------------------------------------------------------------------------------------

    private static Application request(int id, int claimedCustomerId) {
        Application a = new Application();
        a.setId(id);
        a.setName("renamed");
        a.setPkg("pkg" + id);
        a.setCustomerId(claimedCustomerId);
        return a;
    }

    @Test
    public void updatingAnotherCustomersApplicationIsRejectedEvenWithOwnCustomerIdInBody() {
        expectDenied(() -> dao.updateApplication(request(OTHER_APP, CUSTOMER)));
        assertNoMutation();
    }

    @Test
    public void updatingACommonApplicationRequiresSuperAdmin() {
        expectDenied(() -> dao.updateApplication(request(COMMON_APP, CUSTOMER)));
        assertNoMutation();
    }

    @Test
    public void ownApplicationUpdateKeepsThePersistedOwner() {
        dao.updateApplication(request(OWN_APP, OTHER_CUSTOMER));
        List<Call> updates = fake.callsTo("updateApplication");
        assertEquals(1, updates.size());
        assertEquals(CUSTOMER, ((Application) updates.get(0).args[0]).getCustomerId());
    }

    @Test
    public void superAdminMayUpdateAnyCustomersApplication() {
        login(Fakes.superAdmin(1, MASTER));
        dao.updateApplication(request(OTHER_APP, MASTER));
        List<Call> updates = fake.callsTo("updateApplication");
        assertEquals(1, updates.size());
        assertEquals(OTHER_CUSTOMER, ((Application) updates.get(0).args[0]).getCustomerId());
    }

    @Test
    public void applicationUpdateSqlIsBoundToTheOwner() {
        String sql = Fakes.sqlOf(ApplicationMapper.class, "updateApplication").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, Fakes.whereOf(sql).contains("customerid"));
        assertFalse("the owner is never rewritten by an update: " + sql, sql.contains("customerid=#{customerid},"));
    }

    // ---- APK hashes --------------------------------------------------------------------------------------------

    private static final String HASH = "apkHash";

    @Test
    public void apkHashOfOwnVersionIsSaved() {
        assertTrue(dao.saveApkHash(OWN_VERSION, HASH));
        assertEquals(1, fake.callsTo("saveApkFileHash").size());
    }

    @Test
    public void apkHashOfAnotherCustomersVersionIsRejected() {
        expectDenied(() -> dao.saveApkHash(OTHER_VERSION, HASH));
        assertTrue(fake.callsTo("saveApkFileHash").isEmpty());
    }

    @Test
    public void apkHashOfACommonVersionRequiresSuperAdmin() {
        expectDenied(() -> dao.saveApkHash(COMMON_VERSION, HASH));
        assertTrue(fake.callsTo("saveApkFileHash").isEmpty());

        login(Fakes.superAdmin(1, MASTER));
        assertTrue(dao.saveApkHash(COMMON_VERSION, HASH));
        assertTrue(dao.saveApkHash(OTHER_VERSION, HASH));
        assertEquals(2, fake.callsTo("saveApkFileHash").size());
    }

    @Test
    public void apkHashOfAnUnknownVersionIsNotSaved() {
        assertFalse(dao.saveApkHash(9999, HASH));
        assertTrue(fake.callsTo("saveApkFileHash").isEmpty());
    }

    // ---- versions ----------------------------------------------------------------------------------------------

    private static ApplicationVersion versionRequest(int id, int applicationId) {
        ApplicationVersion v = new ApplicationVersion();
        v.setId(id);
        v.setApplicationId(applicationId);
        v.setVersion("2");
        v.setUrl("https://other.example.com/x.apk");
        return v;
    }

    @Test
    public void updatingAnotherCustomersVersionUnderAnOwnedApplicationIsRejected() {
        expectDenied(() -> dao.updateApplicationVersion(versionRequest(OTHER_VERSION, OWN_APP)));
        assertNoMutation();
    }

    @Test
    public void updatingACommonVersionUnderAnOwnedApplicationIsRejected() {
        expectDenied(() -> dao.updateApplicationVersion(versionRequest(COMMON_VERSION, OWN_APP)));
        assertNoMutation();
    }

    @Test
    public void ownVersionUpdateIsBoundToItsApplication() {
        dao.updateApplicationVersion(versionRequest(OWN_VERSION, OWN_APP));
        assertEquals(1, fake.callsTo("updateApplicationVersion").size());
        String where = Fakes.whereOf(Fakes.sqlOf(ApplicationMapper.class, "updateApplicationVersion"));
        assertTrue(where, where.contains("applicationid"));
    }

    @Test
    public void addingAVersionToAnotherCustomersApplicationIsRejected() {
        ApplicationVersion v = versionRequest(0, OTHER_APP);
        v.setId(null);
        expectDenied(() -> dao.insertApplicationVersion(v));
        assertNoMutation();
    }

    // ---- application <-> configuration links -------------------------------------------------------------------

    private static ApplicationConfigurationLink link(Integer id, int configurationId, int applicationId, int action) {
        ApplicationConfigurationLink l = new ApplicationConfigurationLink();
        l.setId(id);
        l.setConfigurationId(configurationId);
        l.setApplicationId(applicationId);
        l.setAction(action);
        l.setShowIcon(true);
        return l;
    }

    private static LinkConfigurationsToAppRequest appLinks(int applicationId, ApplicationConfigurationLink... links) {
        LinkConfigurationsToAppRequest r = new LinkConfigurationsToAppRequest();
        r.setApplicationId(applicationId);
        r.setConfigurations(Fakes.list(links));
        return r;
    }

    /** True when any recorded mutation mentions the configuration id, directly or inside a link list. */
    private boolean touchesConfiguration(int configurationId) {
        for (Call c : fake.callsStartingWith("update", "delete", "insert", "uninstall")) {
            if (c.method.startsWith("recheck")) continue;
            for (Object arg : c.args) {
                if (arg instanceof Integer && (Integer) arg == configurationId && c.method.contains("Links")) return true;
                if (arg instanceof ApplicationConfigurationLink
                        && ((ApplicationConfigurationLink) arg).getConfigurationId() == configurationId) return true;
                if (arg instanceof Collection) {
                    for (Object o : (Collection<?>) arg) {
                        if (o instanceof ApplicationConfigurationLink
                                && ((ApplicationConfigurationLink) o).getConfigurationId() == configurationId) return true;
                        if (o instanceof ApplicationVersionConfigurationLink
                                && ((ApplicationVersionConfigurationLink) o).getConfigurationId() == configurationId) return true;
                    }
                }
            }
            if (c.method.equals("uninstallOtherVersions") && Arrays.asList(c.args).contains(configurationId)) return true;
        }
        return false;
    }

    @Test
    public void linkDeleteAndUpdateAreBoundToTheApplicationAndCallersConfigurations() {
        LinkConfigurationsToAppRequest req = appLinks(OWN_APP,
                link(99, OWN_CONFIG, OWN_APP, 0),          // someone else's link id behind an owned config
                link(98, OWN_CONFIG_2, OWN_APP, 1));
        dao.updateApplicationConfigurations(req);

        List<Call> deletes = fake.callsTo("deleteApplicationConfigurationLink");
        assertEquals(fake.calls.toString(), 1, deletes.size());
        List<Object> args = Arrays.asList(deletes.get(0).args);
        assertTrue(args.toString(), args.contains(99));
        assertTrue("bound to the authorized application: " + args, args.contains(OWN_APP));
        assertTrue("bound to the caller's customer: " + args, args.contains(CUSTOMER));

        List<Call> updates = fake.callsTo("updateApplicationConfigurationLink");
        assertEquals(1, updates.size());
        List<Object> uargs = Arrays.asList(updates.get(0).args);
        assertTrue(uargs.toString(), uargs.contains(OWN_APP) && uargs.contains(CUSTOMER));

        for (String m : new String[]{"deleteApplicationConfigurationLink", "updateApplicationConfigurationLink",
                "deleteApplicationConfigurationLinksForSamePkg", "uninstallOtherVersions"}) {
            String where = Fakes.whereOf(Fakes.sqlOf(ApplicationMapper.class, m));
            assertTrue(m + ": " + where, where.contains("customerid"));
        }
        for (String m : new String[]{"deleteApplicationConfigurationLink", "updateApplicationConfigurationLink"}) {
            String where = Fakes.whereOf(Fakes.sqlOf(ApplicationMapper.class, m));
            assertTrue(m + ": " + where, where.contains("applicationid"));
        }
    }

    @Test
    public void linksToAnotherCustomersConfigurationAreDropped() {
        LinkConfigurationsToAppRequest req = appLinks(OWN_APP,
                link(97, OTHER_CONFIG, OWN_APP, 1),
                link(null, OTHER_CONFIG, OWN_APP, 1),
                link(null, OWN_CONFIG, OWN_APP, 1));
        dao.updateApplicationConfigurations(req);

        assertFalse(fake.calls.toString(), touchesConfiguration(OTHER_CONFIG));
        assertTrue("the owned configuration is still linked: " + fake.calls, touchesConfiguration(OWN_CONFIG));
        assertTrue("dropped links are not reported back for notification",
                req.getConfigurations().stream().noneMatch(l -> l.getConfigurationId() == OTHER_CONFIG));
    }

    @Test
    public void linkingAnotherCustomersApplicationIsRejected() {
        expectDenied(() -> dao.updateApplicationConfigurations(appLinks(OTHER_APP,
                link(97, OWN_CONFIG, OTHER_APP, 0),
                link(null, OWN_CONFIG, OTHER_APP, 1))));
        assertNoMutation();
    }

    @Test
    public void aCommonApplicationMayBeLinkedToOwnConfigurations() {
        dao.updateApplicationConfigurations(appLinks(COMMON_APP, link(null, OWN_CONFIG, COMMON_APP, 1)));
        List<Call> inserts = fake.callsTo("insertApplicationConfigurations");
        assertEquals(fake.calls.toString(), 1, inserts.size());
        assertEquals(COMMON_APP, inserts.get(0).args[0]);
    }

    @Test
    public void aUserLimitedToSomeConfigurationsCannotTouchOthers() {
        login(Fakes.limitedToConfigurations(Fakes.user(6, CUSTOMER, "edit_applications"), OWN_CONFIG));
        dao.updateApplicationConfigurations(appLinks(OWN_APP,
                link(96, OWN_CONFIG_2, OWN_APP, 0),
                link(null, OWN_CONFIG, OWN_APP, 1)));
        assertFalse(fake.calls.toString(), touchesConfiguration(OWN_CONFIG_2));
        assertTrue(fake.callsTo("deleteApplicationConfigurationLink").isEmpty());
    }

    @Test
    public void deletingAnotherCustomersVersionIsRejected() {
        expectDenied(() -> dao.removeApplicationVersionById(OTHER_VERSION));
        assertNoMutation();
        assertTrue(fake.calls.toString(), fake.callsTo("recalculateLatestVersion").isEmpty());
        String where = Fakes.whereOf(Fakes.sqlOf(ApplicationMapper.class, "removeApplicationVersionById"));
        assertTrue(where, where.contains("applicationid"));
    }

    @Test
    public void ownVersionCanBeDeleted() {
        dao.removeApplicationVersionById(OWN_VERSION);
        assertEquals(fake.calls.toString(), 1, fake.callsTo("removeApplicationVersionById").size());
    }

    @Test
    public void applicationDetailsAreServedForOwnAndCommonApplicationsOnly() {
        com.hmdm.rest.resource.ApplicationResource resource =
                new com.hmdm.rest.resource.ApplicationResource(dao, null, null, null, "target");
        login(Fakes.user(5, CUSTOMER, "applications"));
        com.hmdm.rest.json.Response other = resource.getApplication(OTHER_APP);
        assertTrue("another customer's application must not be returned",
                other.getStatus() != com.hmdm.rest.json.Response.ResponseStatus.OK || other.getData() == null);
        assertEquals(OWN_APP, ((Application) resource.getApplication(OWN_APP).getData()).getId().intValue());
        assertEquals(COMMON_APP, ((Application) resource.getApplication(COMMON_APP).getData()).getId().intValue());
    }

    @Test
    public void linkChangesAreBoundToTheConfigurationThatWasAuthorized() {
        // Limited to configuration 5: a link id from configuration 6 must not be reachable by labelling it as 5.
        login(Fakes.limitedToConfigurations(Fakes.user(6, CUSTOMER, "edit_applications"), OWN_CONFIG));
        dao.updateApplicationConfigurations(appLinks(OWN_APP,
                link(96, OWN_CONFIG, OWN_APP, 0),
                link(95, OWN_CONFIG, OWN_APP, 1)));

        List<Call> deletes = fake.callsTo("deleteApplicationConfigurationLink");
        assertEquals(fake.calls.toString(), 1, deletes.size());
        assertTrue("delete bound to the authorized configuration: " + Arrays.toString(deletes.get(0).args),
                Arrays.asList(deletes.get(0).args).contains(OWN_CONFIG));
        for (String m : new String[]{"deleteApplicationConfigurationLink", "updateApplicationConfigurationLink"}) {
            String where = Fakes.whereOf(Fakes.sqlOf(ApplicationMapper.class, m)).replace(" ", "");
            assertTrue(m + ": " + where, where.matches(".*configurationid=#\\{(link\\.)?configurationid\\}.*"));
        }
    }

    @Test
    public void androidAppSaveUpdatesOnlyTheStoredLatestVersionOfThatApplication() {
        com.hmdm.rest.resource.ApplicationResource resource =
                new com.hmdm.rest.resource.ApplicationResource(dao, null, null, null, "target");
        Application body = request(OWN_APP, CUSTOMER);
        body.setUrl("https://mdm.example.com/new.apk");
        body.setLatestVersion(OWN_APP_2_VERSION);   // a version of another application

        resource.updateApplication(body);

        for (Call c : fake.callsTo("updateApplicationVersion")) {
            ApplicationVersion v = (ApplicationVersion) c.args[0];
            assertEquals("only the saved application's own latest version may change",
                    Integer.valueOf(OWN_VERSION), v.getId());
        }
        assertEquals(fake.calls.toString(), 1, fake.callsTo("updateApplicationVersion").size());
    }

    // ---- application version <-> configuration links ------------------------------------------------------------

    private static ApplicationVersionConfigurationLink versionLink(int configurationId, int action) {
        ApplicationVersionConfigurationLink l = new ApplicationVersionConfigurationLink();
        l.setConfigurationId(configurationId);
        l.setAction(action);
        l.setShowIcon(true);
        return l;
    }

    @Test
    public void versionLinksToAnotherCustomersConfigurationAreDropped() {
        LinkConfigurationsToAppVersionRequest req = new LinkConfigurationsToAppVersionRequest();
        req.setApplicationVersionId(OWN_VERSION);
        req.setConfigurations(Fakes.list(versionLink(OTHER_CONFIG, 1), versionLink(OWN_CONFIG, 1)));
        User user = SecurityContext.get().getCurrentUser().get();

        dao.updateApplicationVersionConfigurations(req, user);

        assertFalse(fake.calls.toString(), touchesConfiguration(OTHER_CONFIG));
        assertTrue(fake.calls.toString(), touchesConfiguration(OWN_CONFIG));
    }
}
