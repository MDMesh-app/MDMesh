package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.domain.BrandRow;
import com.mdmesh.policies.persistence.domain.GroupLink;
import com.mdmesh.policies.persistence.domain.GroupRow;
import com.mdmesh.policies.persistence.mapper.GroupMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An in-memory GroupMapper with the tables' keys: a brand is in one group per customer, a phone in one group, a
 * duplicate insert fails as the database would. Phones and configurations are set up by the test.
 */
public class FakeGroupMapper implements GroupMapper {
    /** deviceId → customerId, for phones that exist and aren't wiped. */
    public final Map<Integer, Integer> devices = new HashMap<>();
    /** "customerId:configurationId" of existing configurations. */
    public final Set<String> configurations = new HashSet<>();

    public final TreeMap<Integer, GroupRow> groups = new TreeMap<>();
    /** "customerId:brand" → groupId. */
    public final TreeMap<String, Integer> groupBrands = new TreeMap<>();
    /** deviceId → groupId. */
    public final TreeMap<Integer, Integer> groupDevices = new TreeMap<>();
    /** "customerId:brand" → label. */
    public final TreeMap<String, String> brands = new TreeMap<>();
    /** deviceId → brand. */
    public final TreeMap<Integer, String> deviceBrands = new TreeMap<>();
    private int nextId = 1;

    private static String key(int customerId, String brand) {
        return customerId + ":" + brand;
    }

    private GroupRow copy(GroupRow g) {
        GroupRow c = new GroupRow();
        c.setId(g.getId());
        c.setCustomerId(g.getCustomerId());
        c.setName(g.getName());
        Integer cfg = g.getConfigurationId();
        c.setConfigurationId(cfg != null && configurations.contains(g.getCustomerId() + ":" + cfg) ? cfg : null);
        c.setCreatedAt(g.getCreatedAt());
        return c;
    }

    @Override
    public List<GroupRow> groups(int customerId) {
        List<GroupRow> out = new ArrayList<>();
        for (GroupRow g : groups.values()) if (g.getCustomerId() == customerId) out.add(copy(g));
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    @Override
    public GroupRow group(int customerId, int id) {
        GroupRow g = groups.get(id);
        return g != null && g.getCustomerId() == customerId ? copy(g) : null;
    }

    @Override
    public List<GroupLink> groupBrands(int customerId) {
        List<GroupLink> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : groupBrands.entrySet()) {
            String[] k = e.getKey().split(":", 2);
            if (Integer.parseInt(k[0]) != customerId) continue;
            GroupLink l = new GroupLink();
            l.setGroupId(e.getValue());
            l.setBrand(k[1]);
            out.add(l);
        }
        return out;
    }

    @Override
    public List<GroupLink> groupDevices(int customerId) {
        List<GroupLink> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : groupDevices.entrySet()) {
            if (!Integer.valueOf(customerId).equals(devices.get(e.getKey()))) continue;
            GroupLink l = new GroupLink();
            l.setGroupId(e.getValue());
            l.setDeviceId(e.getKey());
            out.add(l);
        }
        return out;
    }

    @Override
    public List<BrandRow> brands(int customerId) {
        List<BrandRow> out = new ArrayList<>();
        for (Map.Entry<String, String> e : brands.entrySet()) {
            String[] k = e.getKey().split(":", 2);
            if (Integer.parseInt(k[0]) != customerId) continue;
            BrandRow b = new BrandRow();
            b.setBrand(k[1]);
            b.setLabel(e.getValue());
            out.add(b);
        }
        return out;
    }

    @Override
    public List<BrandRow> deviceBrands(int customerId) {
        List<BrandRow> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : deviceBrands.entrySet()) {
            if (!Integer.valueOf(customerId).equals(devices.get(e.getKey()))) continue;
            BrandRow b = new BrandRow();
            b.setDeviceId(e.getKey());
            b.setBrand(e.getValue());
            out.add(b);
        }
        return out;
    }

    @Override
    public List<Integer> existingDevices(int customerId, Collection<Integer> ids) {
        List<Integer> out = new ArrayList<>();
        for (Integer i : ids) if (Integer.valueOf(customerId).equals(devices.get(i))) out.add(i);
        return out;
    }

    @Override
    public int configurationExists(int customerId, int id) {
        return configurations.contains(customerId + ":" + id) ? 1 : 0;
    }

    @Override
    public int countNamed(int customerId, String name, int exceptId) {
        int n = 0;
        for (GroupRow g : groups.values()) {
            if (g.getCustomerId() == customerId && g.getName().equalsIgnoreCase(name) && g.getId() != exceptId) n++;
        }
        return n;
    }

    @Override
    public int brandUses(int customerId, String brand) {
        int n = groupBrands.containsKey(key(customerId, brand)) ? 1 : 0;
        for (Map.Entry<Integer, String> e : deviceBrands.entrySet()) {
            if (Integer.valueOf(customerId).equals(devices.get(e.getKey())) && e.getValue().equals(brand)) n++;
        }
        return n;
    }

    @Override
    public void insertGroup(GroupRow g) {
        g.setId(nextId++);
        GroupRow stored = new GroupRow();
        stored.setId(g.getId());
        stored.setCustomerId(g.getCustomerId());
        stored.setName(g.getName());
        stored.setConfigurationId(g.getConfigurationId());
        stored.setCreatedAt(g.getCreatedAt());
        groups.put(g.getId(), stored);
    }

    @Override
    public int updateGroup(GroupRow g) {
        GroupRow stored = groups.get(g.getId());
        if (stored == null || stored.getCustomerId() != g.getCustomerId()) return 0;
        stored.setName(g.getName());
        stored.setConfigurationId(g.getConfigurationId());
        return 1;
    }

    @Override
    public int deleteGroup(int customerId, int id) {
        GroupRow g = groups.get(id);
        if (g == null || g.getCustomerId() != customerId) return 0;
        groups.remove(id);
        groupBrands.values().removeIf(v -> v == id);
        groupDevices.values().removeIf(v -> v == id);
        return 1;
    }

    @Override
    public int setPolicy(int customerId, Integer configurationId, Collection<Integer> ids) {
        int n = 0;
        for (Integer id : ids) {
            GroupRow g = groups.get(id);
            if (g != null && g.getCustomerId() == customerId) {
                g.setConfigurationId(configurationId);
                n++;
            }
        }
        return n;
    }

    @Override
    public void clearBrands(int groupId) {
        groupBrands.values().removeIf(v -> v == groupId);
    }

    @Override
    public void releaseBrands(int customerId, Collection<String> list) {
        for (String b : list) groupBrands.remove(key(customerId, b));
    }

    @Override
    public void insertBrands(int customerId, int groupId, Collection<String> list) {
        for (String b : list) {
            if (groupBrands.containsKey(key(customerId, b))) throw new IllegalStateException("duplicate key: " + b);
            groupBrands.put(key(customerId, b), groupId);
        }
    }

    @Override
    public void clearDevices(int groupId) {
        groupDevices.values().removeIf(v -> v == groupId);
    }

    @Override
    public void releaseDevices(int customerId, Collection<Integer> ids) {
        for (Integer i : ids) groupDevices.remove(i);
    }

    @Override
    public void insertDevices(int customerId, int groupId, Collection<Integer> ids) {
        for (Integer i : ids) {
            if (groupDevices.containsKey(i)) throw new IllegalStateException("duplicate key: " + i);
            groupDevices.put(i, groupId);
        }
    }

    @Override
    public void saveBrand(int customerId, String brand, String label) {
        brands.put(key(customerId, brand), label);
    }

    @Override
    public int deleteBrand(int customerId, String brand) {
        return brands.remove(key(customerId, brand)) != null ? 1 : 0;
    }

    @Override
    public void clearDeviceBrands(int customerId, Collection<Integer> ids) {
        for (Integer i : ids) deviceBrands.remove(i);
    }

    @Override
    public void insertDeviceBrands(int customerId, String brand, Collection<Integer> ids) {
        for (Integer i : ids) {
            if (deviceBrands.containsKey(i)) throw new IllegalStateException("duplicate key: " + i);
            deviceBrands.put(i, brand);
        }
    }
}
