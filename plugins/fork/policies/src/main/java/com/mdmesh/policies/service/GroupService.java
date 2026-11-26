package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.GroupDAO;
import com.mdmesh.policies.persistence.domain.BrandRow;
import com.mdmesh.policies.persistence.domain.GroupLink;
import com.mdmesh.policies.persistence.domain.GroupRow;
import com.mdmesh.policies.persistence.mapper.GroupMapper;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Device groups and brands.
 *
 * <p>A group covers whole brands (every phone of them, new ones too) and phones picked by hand, and points at one policy
 * or none yet. A phone picked into a group, or given a brand by hand, follows that over the brand it reports; a brand
 * and a phone are in one group at most. Which group a phone is in, and whether it is on its group's policy, the
 * console works out from what {@link #view} returns. Nothing here sends anything to a phone or moves one: phones go to
 * their group's policy when someone presses Apply (core's bulk move, which checks the user's access per phone).
 */
@Singleton
public class GroupService {
    static final int MAX_NAME = 100;
    static final int MAX_BRANDS = 200;
    static final int MAX_DEVICES = 10000;
    /** A brand key: what a phone reports, lower-cased (`oppo`, `nothing`, `hmd global`). */
    private static final Pattern BRAND = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} ._+&'-]{0,39}$");
    /** The key the console uses for phones that report no brand: never a group's or a phone's brand. */
    static final String UNKNOWN = "unknown";

    private final GroupMapper mapper;
    private final GroupDAO dao;

    @Inject
    public GroupService(GroupMapper mapper, GroupDAO dao) {
        this.mapper = mapper;
        this.dao = dao;
    }

    /** A group as saved: a name, the policy (configuration id) or null, its brands and its picked phones (device ids). */
    public static class Input {
        public String name;
        public Integer configurationId;
        public List<String> brands = new ArrayList<>();
        public List<Integer> devices = new ArrayList<>();
    }

    /** Everything the console needs: groups with their brands and phones, brands made by hand, brands set on phones. */
    public Map<String, Object> view(int customerId) {
        Map<Integer, Map<String, Object>> byId = new LinkedHashMap<>();
        for (GroupRow g : mapper.groups(customerId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("configurationId", g.getConfigurationId());
            m.put("brands", new ArrayList<String>());
            m.put("devices", new ArrayList<Integer>());
            m.put("createdAt", g.getCreatedAt());
            byId.put(g.getId(), m);
        }
        for (GroupLink l : mapper.groupBrands(customerId)) {
            Map<String, Object> g = byId.get(l.getGroupId());
            if (g != null) list(g, "brands").add(l.getBrand());
        }
        for (GroupLink l : mapper.groupDevices(customerId)) {
            Map<String, Object> g = byId.get(l.getGroupId());
            if (g != null) list(g, "devices").add(l.getDeviceId());
        }
        List<Map<String, Object>> brands = new ArrayList<>();
        for (BrandRow b : mapper.brands(customerId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("brand", b.getBrand());
            m.put("label", b.getLabel());
            brands.add(m);
        }
        List<Map<String, Object>> deviceBrands = new ArrayList<>();
        for (BrandRow b : mapper.deviceBrands(customerId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", b.getDeviceId());
            m.put("brand", b.getBrand());
            deviceBrands.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", new ArrayList<>(byId.values()));
        out.put("brands", brands);
        out.put("deviceBrands", deviceBrands);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Map<String, Object> m, String key) {
        return (List<T>) m.get(key);
    }

    /** Creates ({@code id} null) or updates a group. Its brands and phones leave any other group. Returns its id. */
    public int save(int customerId, Integer id, Input in, long now) {
        if (in == null) throw new JobService.Refused("Nothing to save.");
        String name = in.name == null ? "" : in.name.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new JobService.Refused("Give the group a name.");
        if (name.length() > MAX_NAME) throw new JobService.Refused("The name is too long (" + MAX_NAME + " characters at most).");
        if (mapper.countNamed(customerId, name, id == null ? -1 : id) > 0) {
            throw new JobService.Refused("There's a group called " + name + " already.");
        }
        Integer policy = in.configurationId;
        if (policy != null && mapper.configurationExists(customerId, policy) == 0) throw new JobService.Refused("No such policy.");
        Set<String> brands = brandKeys(in.brands);
        if (brands.size() > MAX_BRANDS) throw new JobService.Refused("Too many brands (" + MAX_BRANDS + " at most).");
        Set<Integer> devices = phones(customerId, in.devices);
        GroupRow g = new GroupRow();
        g.setId(id);
        g.setCustomerId(customerId);
        g.setName(name);
        g.setConfigurationId(policy);
        g.setCreatedAt(now);
        Integer saved = dao.save(g, brands, devices);
        if (saved == null) throw new JobService.Refused("No such group.");
        return saved;
    }

    public void delete(int customerId, int id) {
        if (mapper.deleteGroup(customerId, id) == 0) throw new JobService.Refused("No such group.");
    }

    /** Picks phones into a group, or with null takes them out of the group they were picked into (back to their brand's). */
    public void setMembers(int customerId, List<Integer> deviceIds, Integer groupId) {
        Set<Integer> devices = phones(customerId, deviceIds);
        if (devices.isEmpty()) throw new JobService.Refused("Pick some phones.");
        if (groupId != null && mapper.group(customerId, groupId) == null) throw new JobService.Refused("No such group.");
        dao.setMembers(customerId, devices, groupId);
    }

    /** Sets a brand on phones by hand, or with null lets them show the brand they report again. */
    public void setDeviceBrands(int customerId, List<Integer> deviceIds, String brand) {
        Set<Integer> devices = phones(customerId, deviceIds);
        if (devices.isEmpty()) throw new JobService.Refused("Pick some phones.");
        String key = brand == null ? null : brandKey(brand);
        dao.setDeviceBrands(customerId, devices, key);
    }

    /** Makes a brand by hand (or renames one), so it can be set on phones and given to a group. Returns its key. */
    public String createBrand(int customerId, String label) {
        String shown = label == null ? "" : label.trim().replaceAll("\\s+", " ");
        String key = brandKey(shown);
        mapper.saveBrand(customerId, key, shown);
        return key;
    }

    /** Deletes a brand made by hand, unless a phone or a group still uses it. */
    public void deleteBrand(int customerId, String brand) {
        String key = brandKey(brand);
        int uses = mapper.brandUses(customerId, key);
        if (uses > 0) throw new JobService.Refused("Phones or a group still use this brand: change them first.");
        if (mapper.deleteBrand(customerId, key) == 0) throw new JobService.Refused("No such brand.");
    }

    /** Gives these groups to a policy (when it's made, or from its Groups tab), or with null takes their policy away. */
    public void setPolicy(int customerId, Integer configurationId, List<Integer> groupIds) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (groupIds != null) for (Integer i : groupIds) if (i != null) ids.add(i);
        if (ids.isEmpty()) return;
        if (configurationId != null && mapper.configurationExists(customerId, configurationId) == 0) {
            throw new JobService.Refused("No such policy.");
        }
        mapper.setPolicy(customerId, configurationId, ids);
    }

    /** A brand as one key, as the console keys what phones report; refused when it can't be one. */
    static String brandKey(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (s.isEmpty()) throw new JobService.Refused("Give the brand a name.");
        if (UNKNOWN.equals(s) || !BRAND.matcher(s).matches()) {
            throw new JobService.Refused("A brand name is up to 40 letters, digits and spaces.");
        }
        return s;
    }

    private static Set<String> brandKeys(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) for (String b : raw) out.add(brandKey(b));
        return out;
    }

    /** The customer's phones among these ids; any other id is refused (another customer's, deleted or wiped). */
    private Set<Integer> phones(int customerId, Collection<Integer> raw) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (raw != null) for (Integer i : raw) if (i != null) ids.add(i);
        if (ids.size() > MAX_DEVICES) throw new JobService.Refused("Too many phones at once (" + MAX_DEVICES + " at most).");
        if (ids.isEmpty()) return ids;
        Set<Integer> found = new HashSet<>();
        for (List<Integer> part : GroupDAO.chunks(ids)) found.addAll(mapper.existingDevices(customerId, part));
        if (found.size() != ids.size()) throw new JobService.Refused("Some of these phones no longer exist. Reload and try again.");
        return ids;
    }
}
