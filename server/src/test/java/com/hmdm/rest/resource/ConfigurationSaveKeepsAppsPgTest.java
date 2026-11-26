/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.notification.PushService;
import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.PgDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.ws.rs.BadRequestException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PUT /configurations on real PostgreSQL: a save that leaves out the applications (or sends null, as GET answers)
 * keeps the stored ones, and the rules that depend on them are checked against those.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set ({@link PgDatabase}).</p>
 */
public class ConfigurationSaveKeepsAppsPgTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ClassRule
    public static final PgDatabase DB = new PgDatabase("config_it_");

    private ConfigurationResource resource;
    private int customer;
    private int installed, installedVersion;
    private int blocked, blockedVersion;      // in the configuration as "Do not install"
    private int outside, outsideVersion;      // not in the configuration
    private int id;

    @Before
    public void setUp() throws Exception {
        customer = DB.customer();
        installed = app("com.example.kiosk");
        installedVersion = latest(installed);
        blocked = app("com.example.blocked");
        blockedVersion = latest(blocked);
        outside = app("com.example.outside");
        outsideVersion = latest(outside);
        id = DB.configuration(customer, "warehouse");
        // No link uses the app's latest version, so a link written again from the library would show.
        DB.sql("INSERT INTO applicationVersions (applicationId, version) VALUES (" + installed + ", '2.0')",
                "UPDATE applications SET latestVersion = (SELECT MAX(id) FROM applicationVersions WHERE applicationId = "
                        + installed + ") WHERE id = " + installed,
                "INSERT INTO configurationApplications (configurationId, applicationId, applicationVersionId, action, "
                        + "remove, showIcon, screenOrder) VALUES (" + id + ", " + installed + ", " + installedVersion
                        + ", 1, false, true, 4), (" + id + ", " + blocked + ", " + blockedVersion
                        + ", 0, false, false, null)",
                "INSERT INTO configurationAppPermissions (configurationId, applicationId, permission, state) VALUES ("
                        + id + ", " + installed + ", 'android.permission.CAMERA', 'deny')",
                "UPDATE configurations SET mainAppId = " + installedVersion + ", contentAppId = " + installedVersion
                        + ", kioskMode = true, mobileEnrollment = true WHERE id = " + id,
                // As every save writes it: the column is nullable, the field a boolean.
                "UPDATE configurations SET scheduleAppUpdate = false WHERE id = " + id);
        SecurityContext.init(Fakes.user(DB.user(customer, PgDatabase.ORG_ADMIN, true), customer,
                "configurations", "add_config", "copy_config"));
        resource = new ConfigurationResource(DB.get(ConfigurationDAO.class), DB.get(ApplicationDAO.class),
                new PushService(null, null, null, null, false), DB.get(CustomerDAO.class), DB.get(UserDAO.class),
                "https://mdm.example.test", Fakes.noExtensionPoints());
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void whatGetAnswersCanBeSavedBackAndChangesNothing() throws Exception {
        String before = stored();
        String revision = revision();
        ObjectNode body = read();
        assertTrue(body.get("applications").isNull());
        save(body);
        assertEquals(before, stored());
        assertEquals(revision, revision());
    }

    @Test
    public void aSaveWithoutTheKeyKeepsTheApps() throws Exception {
        String before = stored();
        ObjectNode body = read();
        body.remove("applications");
        save(body);
        assertEquals(before, stored());
    }

    @Test
    public void anEmptyListRemovesTheAppsAndTheMainApp() throws Exception {
        ObjectNode body = read();
        body.putArray("applications");
        save(body);
        assertEquals("", apps(id));
        assertEquals("none|none|0", mainContentAndRules(id));
    }

    @Test
    public void aListReplacesTheApps() throws Exception {
        ObjectNode body = read();
        body.putArray("applications").addObject().put("id", outside).put("usedVersionId", outsideVersion)
                .put("action", 1);
        save(body);
        assertEquals(outside + ":" + outsideVersion + ":1", apps(id));
        assertEquals("none|none|0", mainContentAndRules(id));
    }

    @Test
    public void aMainAppTheStoredAppsDoNotInstallIsClearedOnASaveWithoutThem() throws Exception {
        for (int version : new int[]{blockedVersion, outsideVersion}) {
            ObjectNode body = read();
            body.remove("applications");
            body.put("mainAppId", version).put("contentAppId", version);
            save(body);
            assertEquals("none|none|1", mainContentAndRules(id));
        }
    }

    @Test
    public void permissionRulesOfASaveWithoutTheAppsAreCheckedAgainstTheStoredOnes() throws Exception {
        ObjectNode body = read();
        body.remove("applications");
        body.putArray("managedAppPermissions");
        for (int app : new int[]{installed, blocked, outside}) {
            body.withArray("managedAppPermissions").addObject().put("applicationId", app)
                    .put("permission", "android.permission.RECORD_AUDIO").put("state", "grant");
        }
        save(body);
        assertEquals(installed + " android.permission.RECORD_AUDIO grant", DB.text("SELECT string_agg(applicationId || "
                + "' ' || permission || ' ' || state, ',') FROM configurationAppPermissions WHERE configurationId = " + id));

        String before = stored();
        body.withArray("managedAppPermissions").addObject().put("applicationId", installed)
                .put("permission", "android.permission.CAMERA").put("state", "ask");
        try {
            save(body);
            fail("saved a malformed rule");
        } catch (BadRequestException expected) {
            assertEquals(before, stored());
        }
    }

    @Test
    public void aNewConfigurationWithoutTheListHasNoAppsAndNoRules() throws Exception {
        for (boolean withNull : new boolean[]{true, false}) {
            ObjectNode body = read();
            body.remove("id");
            if (withNull) body.putNull("applications"); else body.remove("applications");
            body.put("name", "new " + withNull);
            save(body);
            int created = DB.scalar("SELECT id FROM configurations WHERE customerId = " + customer
                    + " AND name = 'new " + withNull + "'");
            assertEquals("", apps(created));
            assertEquals(0, DB.scalar("SELECT COUNT(*) FROM configurationAppPermissions WHERE configurationId = " + created));
        }
    }

    @Test
    public void aCopyKeepsTheAppsTheRulesAndMobileEnrollment() throws Exception {
        Configuration copy = new Configuration();
        copy.setId(id);
        copy.setName("copy");
        assertEquals(Response.ResponseStatus.OK, resource.copyConfiguration(copy).getStatus());
        int copied = DB.scalar("SELECT id FROM configurations WHERE customerId = " + customer + " AND name = 'copy'");
        assertEquals(apps(id), apps(copied));
        assertEquals(mainContentAndRules(id), mainContentAndRules(copied));
        assertEquals("true", DB.text("SELECT mobileEnrollment::text FROM configurations WHERE id = " + copied));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private int app(String pkg) throws Exception {
        int app = DB.scalar("INSERT INTO applications (pkg, name, customerId) VALUES ('" + pkg + "', '" + pkg + "', "
                + customer + ") RETURNING id");
        DB.sql("UPDATE applications SET latestVersion = " + DB.scalar("INSERT INTO applicationVersions (applicationId, "
                + "version) VALUES (" + app + ", '1.0') RETURNING id") + " WHERE id = " + app);
        return app;
    }

    private int latest(int app) throws Exception {
        return DB.scalar("SELECT latestVersion FROM applications WHERE id = " + app);
    }

    /** The configuration as GET /{id} answers it. */
    private ObjectNode read() throws Exception {
        return (ObjectNode) JSON.readTree(JSON.writeValueAsString(resource.getConfigurationById(id).getData()));
    }

    private void save(ObjectNode body) throws Exception {
        Response r = resource.updateConfiguration(JSON.readValue(body.toString(), Configuration.class));
        assertEquals(r.getMessage(), Response.ResponseStatus.OK, r.getStatus());
    }

    /** Every column of the configuration, its app links (row ids included) and its permission rules. */
    private String stored() throws Exception {
        return DB.text("SELECT row_to_json(c) || '' || (SELECT json_agg(a ORDER BY a.id) FROM configurationApplications a "
                + "WHERE a.configurationId = c.id) || (SELECT json_agg(p ORDER BY p.applicationId) "
                + "FROM configurationAppPermissions p WHERE p.configurationId = c.id) FROM configurations c WHERE id = " + id);
    }

    private String revision() {
        Device device = new Device();
        device.setConfigurationId(id);
        return new ConfigReconciler(DB.get(UnsecureDAO.class), null, Fakes.noExtensionPoints())
                .currentDocument(device).getRevision();
    }

    private static String apps(int configuration) throws Exception {
        return DB.text("SELECT COALESCE(string_agg(applicationId || ':' || applicationVersionId || ':' || action, ',' "
                + "ORDER BY applicationId), '') FROM configurationApplications WHERE configurationId = " + configuration);
    }

    /** The main app, the content app and the number of permission rules. */
    private static String mainContentAndRules(int configuration) throws Exception {
        return DB.text("SELECT COALESCE(mainAppId::text, 'none') || '|' || COALESCE(contentAppId::text, 'none') || '|' || "
                + "(SELECT COUNT(*) FROM configurationAppPermissions p WHERE p.configurationId = c.id) "
                + "FROM configurations c WHERE id = " + configuration);
    }
}
