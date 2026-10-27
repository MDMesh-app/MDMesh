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
        Application a = new Application(); a.setId(id); a.setUsedVersionId(versionId); a.setPkg(pkg); a.setAction(action); a.setShowIcon(true); return a;
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
     * disableFactoryReset is a "block the wipe" flag, so the wire key factoryReset is its INVERSE;
     * disableSafeBoot likewise inverts into safeBoot.
     */
    private static Configuration policiesConfig() {
        Configuration c = new Configuration();
        c.setId(7);
        c.setWifi(false); c.setBluetooth(true); c.setUsbStorage(true);
        c.setDisableScreenshots(false); c.setDisableFactoryReset(true); c.setDisableSafeBoot(true);
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
    public void kiosk_blocks_safe_mode_unless_the_configuration_says_otherwise() {
        // Safe Mode starts the phone's own launcher and gets around kiosk, so a kiosk that leaves it unmanaged blocks it.
        Configuration c = kioskConfig(); c.setDisableSafeBoot(null);
        assertEquals(Boolean.FALSE, DesiredConfigBuilder.build(c, Collections.emptyList()).getPolicies().get("safeBoot"));

        c.setDisableSafeBoot(false);
        assertEquals("an explicit Allow is kept", Boolean.TRUE,
                DesiredConfigBuilder.build(c, Collections.emptyList()).getPolicies().get("safeBoot"));

        c.setDisableSafeBoot(null); c.setKioskMode(false);
        assertFalse("without kiosk, unmanaged stays unmanaged",
                DesiredConfigBuilder.build(c, Collections.emptyList()).getPolicies().containsKey("safeBoot"));
    }

    @Test
    public void kiosk_recents_and_notifications_need_home() {
        // Android refuses them without Home and the whole kiosk entry fails: dropped, Home never turned on.
        Configuration c = kioskConfig(); c.setKioskHome(null); c.setKioskRecents(true); c.setKioskNotifications(true);
        DesiredConfig d = DesiredConfigBuilder.build(c, Collections.emptyList());
        assertNull(d.getKiosk().getFeatures().getHome());
        assertNull(d.getKiosk().getFeatures().getRecents());
        assertNull(d.getKiosk().getFeatures().getNotifications());

        c.setKioskHome(false); c.setKioskRecents(false);
        d = DesiredConfigBuilder.build(c, Collections.emptyList());
        assertEquals("a false is kept (unchanged revision)", Boolean.FALSE, d.getKiosk().getFeatures().getRecents());
        assertNull(d.getKiosk().getFeatures().getNotifications());

        c.setKioskHome(true); c.setKioskRecents(true); c.setKioskNotifications(true);
        d = DesiredConfigBuilder.build(c, Collections.emptyList());
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getRecents());
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getNotifications());
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
    public void installedAppHiddenFromKioskIsNotAllowedOrPinned() {
        Application hidden = app(5, 505, "com.acme.hidden", 1);
        hidden.setShowIcon(false);
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(
                hidden, app(6, 606, "com.acme.visible", 1)));
        assertNull(d.getKiosk().getPinPackage());
        assertEquals(Collections.singletonList("com.acme.visible"), d.getKiosk().getAllowedPackages());
        assertEquals("launcher", d.getKiosk().getMode());
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

    @Test
    public void safe_boot_is_inverted_into_the_blocking_policy() {
        // disableSafeBoot=true means "block Safe Mode", so the agent receives safeBoot=false.
        DesiredConfig blocked = DesiredConfigBuilder.build(policiesConfig(), Collections.emptyList());
        assertEquals("disableSafeBoot=true -> policies.safeBoot=false", Boolean.FALSE,
                blocked.getPolicies().get("safeBoot"));

        Configuration allowed = policiesConfig(); allowed.setDisableSafeBoot(false);
        assertEquals("disableSafeBoot=false -> policies.safeBoot=true", Boolean.TRUE,
                DesiredConfigBuilder.build(allowed, Collections.emptyList()).getPolicies().get("safeBoot"));
    }

    @Test
    public void null_safe_boot_is_unmanaged_and_the_key_is_omitted() {
        Configuration unmanaged = policiesConfig(); unmanaged.setDisableSafeBoot(null);
        DesiredConfig d = DesiredConfigBuilder.build(unmanaged, Collections.emptyList());
        // Configurations without kiosk upgrade with a NULL column and keep their revision: the key stays omitted.
        // Kiosk ones block Safe Mode instead (kiosk_blocks_safe_mode_unless_the_configuration_says_otherwise).
        assertFalse("null disableSafeBoot = not managed", d.getPolicies().containsKey("safeBoot"));
    }

    @Test
    public void safe_boot_alone_changes_the_revision() {
        DesiredConfig blocked = DesiredConfigBuilder.build(policiesConfig(), Collections.emptyList());
        Configuration allowed = policiesConfig(); allowed.setDisableSafeBoot(false);
        assertNotEquals(blocked.getRevision(), DesiredConfigBuilder.build(allowed, Collections.emptyList()).getRevision());

        Configuration unmanaged = policiesConfig(); unmanaged.setDisableSafeBoot(null);
        assertNotEquals(blocked.getRevision(), DesiredConfigBuilder.build(unmanaged, Collections.emptyList()).getRevision());
    }

    @Test
    public void location_do_not_track_is_off_network_is_passive_gps_is_active() {
        Configuration c = policiesConfig();
        c.setRequestUpdates(RequestUpdatesType.DONOTTRACK);
        assertEquals("Do not track must read no location at all", "off",
                DesiredConfigBuilder.build(c, Collections.emptyList()).getLocation().getMode());
        c.setRequestUpdates(RequestUpdatesType.WIFI);
        assertEquals("passive", DesiredConfigBuilder.build(c, Collections.emptyList()).getLocation().getMode());
        c.setRequestUpdates(RequestUpdatesType.GPS);
        assertEquals("active", DesiredConfigBuilder.build(c, Collections.emptyList()).getLocation().getMode());
        c.setRequestUpdates(null);
        assertEquals("the column's own default is DONOTTRACK", "off",
                DesiredConfigBuilder.build(c, Collections.emptyList()).getLocation().getMode());
    }

    @Test
    public void wifi_radio_is_its_own_key_and_wifi_false_still_only_locks_settings() {
        // Stored wifi=false has always meant "lock Wi-Fi settings": it must not start switching the radio off.
        DesiredConfig legacy = DesiredConfigBuilder.build(policiesConfig(), Collections.emptyList());
        assertEquals(Boolean.FALSE, legacy.getPolicies().get("wifi"));
        assertFalse("null wifiRadio = radio not managed", legacy.getPolicies().containsKey("wifiRadio"));

        Configuration off = policiesConfig(); off.setWifiRadio(false);
        DesiredConfig d = DesiredConfigBuilder.build(off, Collections.emptyList());
        assertEquals(Boolean.FALSE, d.getPolicies().get("wifiRadio"));
        assertNotEquals(legacy.getRevision(), d.getRevision());

        Configuration on = policiesConfig(); on.setWifiRadio(true);
        assertEquals(Boolean.TRUE, DesiredConfigBuilder.build(on, Collections.emptyList()).getPolicies().get("wifiRadio"));
        // Existing configurations upgrade with a NULL column: the kiosk golden revision must not move.
        assertEquals(resource("desired-config-kiosk.sha256"),
                DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1))).getRevision());
    }

    @Test
    public void connectivity_is_sent_only_when_managed_and_null_keeps_the_revision() {
        // Existing configurations upgrade with a NULL column (protocol 1.5): no section, and no revision moves.
        DesiredConfig unmanaged = DesiredConfigBuilder.build(policiesConfig(), Collections.emptyList());
        assertNull("null connectivityMode = not managed", unmanaged.getConnectivity());
        assertFalse(DesiredConfigBuilder.canonicalJson(unmanaged).contains("connectivity"));
        assertEquals(resource("desired-config-kiosk.sha256"),
                DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1))).getRevision());

        Configuration always = policiesConfig(); always.setConnectivityMode("alwaysOn");
        DesiredConfig d = DesiredConfigBuilder.build(always, Collections.emptyList());
        assertEquals("alwaysOn", d.getConnectivity().getMode());
        assertTrue(DesiredConfigBuilder.canonicalJson(d).contains("\"connectivity\":{\"mode\":\"alwaysOn\"}"));
        assertNotEquals(unmanaged.getRevision(), d.getRevision());

        Configuration adaptive = policiesConfig(); adaptive.setConnectivityMode("adaptive");
        assertEquals("adaptive", DesiredConfigBuilder.build(adaptive, Collections.emptyList()).getConnectivity().getMode());

        Configuration unknown = policiesConfig(); unknown.setConnectivityMode("turbo");
        DesiredConfig u = DesiredConfigBuilder.build(unknown, Collections.emptyList());
        assertNull("a mode this server doesn't know is not managed", u.getConnectivity());
        assertEquals(unmanaged.getRevision(), u.getRevision());
    }

    @Test
    public void kiosk_volume_lock_is_its_own_opt_in_not_part_of_lock_buttons() {
        // Android mutes the device while volume is locked: lockButtons=true configurations must not start muting.
        Configuration buttons = kioskConfig(); buttons.setKioskLockButtons(true);
        DesiredConfig d = DesiredConfigBuilder.build(buttons, Collections.emptyList());
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getLockButtons());
        assertNull(d.getKiosk().getFeatures().getLockVolume());

        Configuration volume = kioskConfig(); volume.setKioskLockVolume(true);
        assertEquals(Boolean.TRUE,
                DesiredConfigBuilder.build(volume, Collections.emptyList()).getKiosk().getFeatures().getLockVolume());
        // A NULL column (every existing configuration) keeps the golden revision.
        assertEquals(resource("desired-config-kiosk.sha256"),
                DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1))).getRevision());
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
