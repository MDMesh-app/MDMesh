/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class KioskAlertRecipientsTest {

    private static final int CUSTOMER = 4, OTHER_CUSTOMER = 5;
    private static final int LOBBY = 10, STORE = 11;

    private static Device device(Integer... groups) {
        Device d = new Device();
        d.setNumber("1234");
        d.setCustomerId(CUSTOMER);
        List<LookupItem> g = new ArrayList<>();
        for (Integer id : groups) g.add(new LookupItem(id, "g" + id));
        d.setGroups(g);
        return d;
    }

    private static User admin(int id, String mail) {
        User u = Fakes.user(id, CUSTOMER, "edit_devices", "configurations", "settings");
        u.setEmail(mail);
        return u;
    }

    private static User limitedToGroups(User u, int... groups) {
        u.setAllDevicesAvailable(false);
        List<LookupItem> g = new ArrayList<>();
        for (int id : groups) g.add(new LookupItem(id, "g" + id));
        u.setGroups(g);
        return u;
    }

    @Test
    public void theCustomersDeviceManagersWhoCanSeeTheDevice() {
        User superAdmin = Fakes.superAdmin(2, CUSTOMER);
        superAdmin.setEmail("root@example.com");
        superAdmin.setAllDevicesAvailable(false); // a super-admin sees every device regardless
        User inGroup = limitedToGroups(admin(3, "lobby@example.com"), LOBBY);
        User otherGroup = limitedToGroups(admin(4, "store@example.com"), STORE);
        User observer = Fakes.user(7, CUSTOMER, "settings");
        observer.setEmail("observer@example.com");
        User otherTenant = Fakes.user(8, OTHER_CUSTOMER, "edit_devices");
        otherTenant.setEmail("other@example.com");

        assertEquals(Arrays.asList("admin@example.com", "root@example.com", "lobby@example.com"),
                KioskAlertRecipients.of(device(LOBBY), Arrays.asList(
                        admin(1, "admin@example.com"), superAdmin, inGroup, otherGroup, observer, otherTenant, null)));
    }

    @Test
    public void onlyASingleValidAddressPerUserIsMailed() {
        List<String> to = KioskAlertRecipients.of(device(), Arrays.asList(
                admin(1, " a@example.com "),
                admin(2, null),
                admin(3, "   "),
                admin(4, "b@example.com, c@example.com"),
                admin(5, "d@example.com\r\nBcc: e@example.com"),
                admin(6, "not-an-address"),
                admin(7, "Ann <f@example.com>")));
        assertEquals(Arrays.asList("a@example.com", "f@example.com"), to);
    }

    @Test
    public void eachAddressIsMailedOnce() {
        assertEquals(Collections.singletonList("a@example.com"), KioskAlertRecipients.of(device(),
                Arrays.asList(admin(1, "a@example.com"), admin(2, "A@Example.com"))));
    }

    @Test
    public void aGroupLimitedUserIsNotMailedAboutADeviceWithoutGroups() {
        User limited = limitedToGroups(admin(3, "lobby@example.com"), LOBBY);
        assertEquals(Collections.emptyList(), KioskAlertRecipients.of(device(), Collections.singletonList(limited)));
    }

    @Test
    public void aUserWithoutARoleIsSkipped() {
        User u = admin(1, "a@example.com");
        u.setUserRole(null);
        assertEquals(Collections.emptyList(), KioskAlertRecipients.of(device(), Collections.singletonList(u)));
    }
}
