package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.ManagedAppPermission;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.DesiredConfigBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.BadRequestException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** PUT /configurations: managedAppPermissions are checked before anything is saved, and kept only for installed apps. */
public class ConfigurationResourceAppPermissionsTest {

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(5, 1, "configurations"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Application app(int id, int action) {
        Application a = new Application();
        a.setId(id);
        a.setAction(action);
        return a;
    }

    private static ManagedAppPermission rule(int appId, String permission, String state) {
        ManagedAppPermission r = new ManagedAppPermission();
        r.setApplicationId(appId);
        r.setPermission(permission);
        r.setState(state);
        return r;
    }

    private static Configuration withRules(ManagedAppPermission... rules) {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("warehouse");
        // 1 installed, 2 "Do not install"; 3 was taken out of the configuration (on the Applications page, say).
        c.setApplications(new ArrayList<>(Arrays.asList(app(1, DesiredConfigBuilder.ACTION_INSTALL), app(2, 0))));
        c.setManagedAppPermissions(rules == null ? null : new ArrayList<>(Arrays.asList(rules)));
        return c;
    }

    private static void refused(Configuration c) {
        try {
            new ConfigurationResource().updateConfiguration(c);
            fail("saved " + c.getManagedAppPermissions().size() + " rules");
        } catch (BadRequestException expected) {
            // refused before any DAO is touched (none is wired)
        }
    }

    @Test
    public void rulesForAppsTheSaveDoesNotInstallAreDroppedNotRefused() {
        Configuration c = withRules(rule(1, "android.permission.CAMERA", "deny"),
                rule(2, "android.permission.CAMERA", "deny"), rule(3, "android.permission.RECORD_AUDIO", "grant"));
        ConfigurationResource.normalizeManagedAppPermissions(c);
        assertEquals(1, c.getManagedAppPermissions().size());
        assertEquals(Integer.valueOf(1), c.getManagedAppPermissions().get(0).getApplicationId());
    }

    @Test
    public void aSaveWithoutTheListKeepsWhatIsStored() {
        Configuration c = withRules((ManagedAppPermission[]) null);
        ConfigurationResource.normalizeManagedAppPermissions(c);
        assertNull(c.getManagedAppPermissions());
    }

    @Test
    public void malformedRulesAreABadRequest() {
        refused(withRules(rule(1, "android.permission.CAMERA", "ask")));
        refused(withRules(rule(1, "android.permission.CAMERA", null)));
        refused(withRules(rule(1, "com.example.permission.CAMERA", "deny")));
        refused(withRules(rule(1, "android.permission.camera", "deny")));
        refused(withRules(rule(1, "android.permission.CAMERA'); DROP TABLE x;--", "deny")));
        refused(withRules(rule(1, null, "deny")));
        refused(withRules(rule(1, "android.permission.CAMERA", "deny"), rule(1, "android.permission.CAMERA", "grant")));
        StringBuilder longName = new StringBuilder("android.permission.");
        while (longName.length() <= ConfigurationResource.MAX_PERMISSION_LENGTH) longName.append('A');
        refused(withRules(rule(1, longName.toString(), "deny")));
        // Malformed is refused even for an app the save drops.
        refused(withRules(rule(3, "android.permission.CAMERA", "ask")));
    }

    @Test
    public void tooManyRulesForOneAppAreABadRequest() {
        List<ManagedAppPermission> rules = new ArrayList<>();
        for (int i = 0; i <= ConfigurationResource.MAX_RULES_PER_APP; i++) {
            rules.add(rule(1, "android.permission.P" + i, "deny"));
        }
        refused(withRules(rules.toArray(new ManagedAppPermission[0])));
        rules.remove(0);
        Configuration atTheLimit = withRules(rules.toArray(new ManagedAppPermission[0]));
        ConfigurationResource.normalizeManagedAppPermissions(atTheLimit);
        assertEquals(ConfigurationResource.MAX_RULES_PER_APP, atTheLimit.getManagedAppPermissions().size());
    }

    @Test
    public void aCopyKeepsTheRules() {
        Configuration c = withRules(rule(1, "android.permission.CAMERA", "deny"));
        Configuration copy = c.newCopy();
        assertEquals(1, copy.getManagedAppPermissions().size());
        assertEquals("deny", copy.getManagedAppPermissions().get(0).getState());
    }
}
