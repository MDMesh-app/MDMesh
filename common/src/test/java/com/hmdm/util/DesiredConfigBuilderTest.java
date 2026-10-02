package com.hmdm.util;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.IconSize;
import com.hmdm.persistence.domain.RequestUpdatesType;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Scanner;
import static org.junit.Assert.*;

public class DesiredConfigBuilderTest {

    /** Application ids and version ids are deliberately distinct: mainAppId is an applicationVersions.id. */
    private static Application app(int id, int versionId, String pkg, int action) {
        Application a = new Application(); a.setId(id); a.setUsedVersionId(versionId); a.setPkg(pkg); a.setAction(action); return a;
    }

    private static Configuration kioskConfig() {
        Configuration c = new Configuration();
        c.setId(12); c.setWifi(true); c.setBluetooth(false); c.setUsbStorage(null); c.setDisableScreenshots(true);
        c.setKioskMode(true); c.setMainAppId(505); c.setKioskExit(true); c.setKioskHome(true); c.setKioskRecents(false);
        c.setPassword("s3cret"); c.setBackgroundColor("#000000"); c.setTextColor("#ffffff"); c.setIconSize(IconSize.LARGE);
        c.setRequestUpdates(RequestUpdatesType.GPS);
        return c;
    }

    /**
     * Every policy key managed: the kiosk counterpart of {@link #kioskConfig()} for the flat policy block.
     * disableFactoryReset is a "block the wipe" flag, so the wire key factoryReset is its INVERSE.
     */
    private static Configuration policiesConfig() {
        Configuration c = new Configuration();
        c.setId(7);
        c.setWifi(false); c.setBluetooth(true); c.setUsbStorage(true);
        c.setDisableScreenshots(false); c.setDisableFactoryReset(true);
        c.setRequestUpdates(RequestUpdatesType.DONOTTRACK);
        return c;
    }

    private static String resource(String name) {
        InputStream in = DesiredConfigBuilderTest.class.getResourceAsStream("/contract/v1/" + name);
        return new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next().trim();
    }

    @Test
    public void tri_state_policies_only_include_managed_keys_and_screenshots_is_inverted() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Collections.emptyList());
        assertEquals(Boolean.TRUE, d.getPolicies().get("wifi"));
        assertEquals(Boolean.FALSE, d.getPolicies().get("bluetooth"));
        assertFalse("null usbStorage = not managed", d.getPolicies().containsKey("usbStorage"));
        assertEquals("disableScreenshots=true -> screenshots=false", Boolean.FALSE, d.getPolicies().get("screenshots"));
    }

    @Test
    public void kiosk_single_when_main_app_is_the_only_install_app() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1), app(9, 909, "com.acme.old", 2)));
        assertEquals("single", d.getKiosk().getMode());
        assertEquals("com.acme.pos", d.getKiosk().getPinPackage());
        assertEquals(Collections.singletonList("com.acme.pos"), d.getKiosk().getAllowedPackages());
        assertEquals("visible", d.getKiosk().getExitMode());
        assertEquals("LARGE", d.getKiosk().getTheme().getIconSize());
        assertEquals("active", d.getLocation().getMode());
    }

    @Test
    public void kiosk_launcher_when_several_apps_and_main_app_first() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(1, 101, "com.b", 1), app(5, 505, "com.acme.pos", 1)));
        assertEquals("launcher", d.getKiosk().getMode());
        assertEquals(Arrays.asList("com.acme.pos", "com.b"), d.getKiosk().getAllowedPackages());
    }

    @Test
    public void duplicate_package_rows_for_the_main_app_are_deduplicated() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1), app(6, 606, "com.acme.pos", 1)));
        assertEquals(Collections.singletonList("com.acme.pos"), d.getKiosk().getAllowedPackages());
        assertEquals("single", d.getKiosk().getMode());
    }

    @Test
    public void application_id_equal_to_mainAppId_is_not_the_main_app() {
        // App 505 (id) has version 777; the main app is the row whose usedVersionId is 505.
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(),
                Arrays.asList(app(505, 777, "com.decoy", 1), app(5, 505, "com.acme.pos", 1)));
        assertEquals("com.acme.pos", d.getKiosk().getPinPackage());
        assertEquals(Arrays.asList("com.acme.pos", "com.decoy"), d.getKiosk().getAllowedPackages());

        DesiredConfig onlyDecoy = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(505, 777, "com.decoy", 1)));
        assertNull("id match without version match must not pin", onlyDecoy.getKiosk().getPinPackage());
        assertEquals("launcher", onlyDecoy.getKiosk().getMode());
    }

    @Test
    public void kiosk_absent_when_kioskMode_off() {
        Configuration c = kioskConfig(); c.setKioskMode(false);
        DesiredConfig d = DesiredConfigBuilder.build(c, Collections.emptyList());
        assertNull(d.getKiosk());
        assertFalse(DesiredConfigBuilder.canonicalJson(d).contains("kiosk"));
    }

    @Test
    public void revision_is_stable_and_independent_of_field_order_and_revision_field() {
        DesiredConfig a = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        DesiredConfig b = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(a.getRevision(), b.getRevision());
        assertEquals(64, a.getRevision().length());
        a.setRevision("tampered");
        assertEquals(b.getRevision(), DesiredConfigBuilder.revision(a));
    }

    /** GOLDEN: any change to canonicalisation changes every device's revision fleet-wide. Update deliberately. */
    @Test
    public void golden_canonical_json_and_revision() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(resource("desired-config-kiosk.json"), DesiredConfigBuilder.canonicalJson(d));
        assertEquals(resource("desired-config-kiosk.sha256"), d.getRevision());
    }

    @Test
    public void factory_reset_is_inverted_into_the_blocking_policy() {
        // disableFactoryReset=true means "block the wipe", so the agent receives factoryReset=false.
        DesiredConfig blocked = DesiredConfigBuilder.build(policiesConfig(), Collections.emptyList());
        assertEquals("disableFactoryReset=true -> policies.factoryReset=false", Boolean.FALSE,
                blocked.getPolicies().get("factoryReset"));

        Configuration allowed = policiesConfig(); allowed.setDisableFactoryReset(false);
        assertEquals("disableFactoryReset=false -> policies.factoryReset=true", Boolean.TRUE,
                DesiredConfigBuilder.build(allowed, Collections.emptyList()).getPolicies().get("factoryReset"));
    }

    @Test
    public void null_factory_reset_is_unmanaged_and_the_key_is_omitted() {
        Configuration unmanaged = policiesConfig(); unmanaged.setDisableFactoryReset(null);
        DesiredConfig d = DesiredConfigBuilder.build(unmanaged, Collections.emptyList());
        assertFalse("null disableFactoryReset = not managed", d.getPolicies().containsKey("factoryReset"));
    }

    @Test
    public void factory_reset_alone_changes_the_revision() {
        // The whole point of the persisted column: toggling it must re-apply, so it must move the revision.
        DesiredConfig blocked = DesiredConfigBuilder.build(policiesConfig(), Collections.emptyList());
        Configuration allowed = policiesConfig(); allowed.setDisableFactoryReset(false);
        assertNotEquals(blocked.getRevision(), DesiredConfigBuilder.build(allowed, Collections.emptyList()).getRevision());

        Configuration unmanaged = policiesConfig(); unmanaged.setDisableFactoryReset(null);
        assertNotEquals(blocked.getRevision(), DesiredConfigBuilder.build(unmanaged, Collections.emptyList()).getRevision());
    }

    /** GOLDEN for the flat policy block. Kept separate from the kiosk fixture so an unmanaged
     *  disableFactoryReset there still proves the key is omitted. Update deliberately. */
    @Test
    public void golden_canonical_json_and_revision_policies() {
        DesiredConfig d = DesiredConfigBuilder.build(policiesConfig(), Collections.emptyList());
        assertEquals(resource("desired-config-policies.json"), DesiredConfigBuilder.canonicalJson(d));
        assertEquals(resource("desired-config-policies.sha256"), d.getRevision());
    }
}
