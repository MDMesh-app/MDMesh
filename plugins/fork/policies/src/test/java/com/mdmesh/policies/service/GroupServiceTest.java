package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.GroupDAO;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GroupServiceTest {
    private FakeGroupMapper db;
    private GroupService service;

    @Before
    public void setUp() {
        db = new FakeGroupMapper();
        service = new GroupService(db, new GroupDAO(db));
        for (int id = 1; id <= 6; id++) db.devices.put(id, 1);
        db.devices.put(99, 2);                                   // another customer's phone
        db.configurations.add("1:4");
        db.configurations.add("1:5");
        db.configurations.add("2:8");
    }

    private static GroupService.Input input(String name, Integer policy, List<String> brands, List<Integer> devices) {
        GroupService.Input in = new GroupService.Input();
        in.name = name;
        in.configurationId = policy;
        in.brands = brands;
        in.devices = devices;
        return in;
    }

    private void refused(String message, Runnable r) {
        try {
            r.run();
            fail("expected: " + message);
        } catch (JobService.Refused e) {
            assertEquals(message, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groups() {
        return (List<Map<String, Object>>) service.view(1).get("groups");
    }

    @Test
    public void aGroupCoversBrandsAndPickedPhonesAndMayHaveNoPolicyYet() {
        int id = service.save(1, null, input("  Oppo   family ", null, Arrays.asList("OPPO", " Realme "), Arrays.asList(3, 4)), 100L);
        Map<String, Object> g = groups().get(0);
        assertEquals(id, g.get("id"));
        assertEquals("Oppo family", g.get("name"));
        assertNull(g.get("configurationId"));                    // no policy yet: the workflow's first step
        assertEquals(Arrays.asList("oppo", "realme"), g.get("brands"));
        assertEquals(Arrays.asList(3, 4), g.get("devices"));
    }

    @Test
    public void aBrandOrPhoneTakenByAnotherGroupLeavesTheFirst() {
        int a = service.save(1, null, input("A", 4, Arrays.asList("oppo", "vivo"), Arrays.asList(1, 2)), 1L);
        int b = service.save(1, null, input("B", 5, Collections.singletonList("vivo"), Collections.singletonList(2)), 2L);
        assertEquals(Integer.valueOf(a), db.groupBrands.get("1:oppo"));
        assertEquals(Integer.valueOf(b), db.groupBrands.get("1:vivo"));
        assertEquals(Integer.valueOf(a), db.groupDevices.get(1));
        assertEquals(Integer.valueOf(b), db.groupDevices.get(2));

        service.save(1, a, input("A", 4, Collections.singletonList("oppo"), Collections.<Integer>emptyList()), 3L);
        assertFalse(db.groupDevices.containsKey(1));             // unpicked: back to its brand's group
        assertEquals(Integer.valueOf(b), db.groupDevices.get(2));
    }

    @Test
    public void namesArePlainAndUnique() {
        service.save(1, null, input("Vivo family", null, null, null), 1L);
        refused("Give the group a name.", () -> service.save(1, null, input("   ", null, null, null), 2L));
        refused("There's a group called vivo FAMILY already.", () -> service.save(1, null, input("vivo FAMILY", null, null, null), 2L));
        int id = groups().get(0).get("id") instanceof Integer ? (Integer) groups().get(0).get("id") : -1;
        service.save(1, id, input("Vivo family", 4, null, null), 3L);   // renaming to itself is fine
        assertEquals(4, groups().get(0).get("configurationId"));
    }

    @Test
    public void onlyTheCustomersPoliciesAndPhones() {
        refused("No such policy.", () -> service.save(1, null, input("X", 8, null, null), 1L));
        refused("Some of these phones no longer exist. Reload and try again.",
                () -> service.save(1, null, input("X", 4, null, Arrays.asList(1, 99)), 1L));
        refused("Some of these phones no longer exist. Reload and try again.",
                () -> service.setMembers(1, Collections.singletonList(77), null));
        assertTrue(db.groups.isEmpty());
    }

    @Test
    public void brandsAreOneKeyAndNeverUnknown() {
        assertEquals("hmd global", GroupService.brandKey("  HMD   Global "));
        refused("A brand name is up to 40 letters, digits and spaces.", () -> GroupService.brandKey("unknown"));
        refused("A brand name is up to 40 letters, digits and spaces.", () -> GroupService.brandKey("<script>"));
        refused("Give the brand a name.", () -> GroupService.brandKey(" "));
    }

    @Test
    public void phonesArePickedIntoAGroupOrBackToTheirBrand() {
        int a = service.save(1, null, input("A", null, null, null), 1L);
        service.setMembers(1, Arrays.asList(5, 6), a);
        assertEquals(Integer.valueOf(a), db.groupDevices.get(5));
        service.setMembers(1, Collections.singletonList(5), null);
        assertFalse(db.groupDevices.containsKey(5));
        assertEquals(Integer.valueOf(a), db.groupDevices.get(6));
        refused("No such group.", () -> service.setMembers(1, Collections.singletonList(6), 999));
        refused("Pick some phones.", () -> service.setMembers(1, Collections.<Integer>emptyList(), a));
    }

    @Test
    public void aBrandSetByHandWinsUntilCleared() {
        String key = service.createBrand(1, " Nothing ");
        assertEquals("nothing", key);
        assertEquals("Nothing", db.brands.get("1:nothing"));
        service.setDeviceBrands(1, Arrays.asList(1, 2), "Nothing");
        assertEquals("nothing", db.deviceBrands.get(1));
        refused("Phones or a group still use this brand: change them first.", () -> service.deleteBrand(1, "nothing"));
        service.setDeviceBrands(1, Arrays.asList(1, 2), null);
        assertTrue(db.deviceBrands.isEmpty());
        service.deleteBrand(1, "nothing");
        assertTrue(db.brands.isEmpty());
    }

    @Test
    public void groupsAreGivenToAPolicyWhenItIsMade() {
        int a = service.save(1, null, input("A", null, null, null), 1L);
        int b = service.save(1, null, input("B", 4, null, null), 1L);
        service.setPolicy(1, 5, Arrays.asList(a, b));
        assertEquals(Integer.valueOf(5), db.groups.get(a).getConfigurationId());
        assertEquals(Integer.valueOf(5), db.groups.get(b).getConfigurationId());
        refused("No such policy.", () -> service.setPolicy(1, 8, Collections.singletonList(a)));
        service.setPolicy(1, null, Collections.singletonList(a));
        assertNull(db.groups.get(a).getConfigurationId());
    }

    @Test
    public void deletingAGroupFreesItsBrandsAndPhones() {
        int a = service.save(1, null, input("A", 4, Collections.singletonList("oppo"), Collections.singletonList(1)), 1L);
        service.delete(1, a);
        assertTrue(db.groupBrands.isEmpty());
        assertTrue(db.groupDevices.isEmpty());
        refused("No such group.", () -> service.delete(1, a));
        refused("No such group.", () -> service.save(1, a, input("A", 4, null, null), 2L));
    }

    @Test
    public void anotherCustomerSeesNothing() {
        service.save(1, null, input("A", 4, Collections.singletonList("oppo"), Collections.singletonList(1)), 1L);
        @SuppressWarnings("unchecked")
        List<Object> theirs = (List<Object>) service.view(2).get("groups");
        assertTrue(theirs.isEmpty());
        refused("No such group.", () -> service.delete(2, 1));
    }
}
