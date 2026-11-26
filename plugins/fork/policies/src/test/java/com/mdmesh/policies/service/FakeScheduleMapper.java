package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.domain.AppSchedule;
import com.mdmesh.policies.persistence.domain.GroupLink;
import com.mdmesh.policies.persistence.domain.SchedulePhone;
import com.mdmesh.policies.persistence.mapper.ScheduleMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** App schedules, phones and group brands in memory (customer 1). */
public class FakeScheduleMapper implements ScheduleMapper {
    public final TreeMap<Integer, AppSchedule> schedules = new TreeMap<>();
    public final Map<Integer, List<SchedulePhone>> phones = new HashMap<>();
    public final Map<Integer, String> groups = new HashMap<>();
    public final List<GroupLink> brands = new ArrayList<>();
    private int seq = 1;

    public SchedulePhone phone(int cfg, int id, String number, String caps, String brand, String description) {
        SchedulePhone p = new SchedulePhone();
        p.setId(id);
        p.setNumber(number);
        p.setCapabilities(caps);
        p.setDescription(description);
        p.setTelemetry(brand == null ? null : "{\"hardware\":{\"brand\":\"" + brand + "\"}}");
        phones.computeIfAbsent(cfg, k -> new ArrayList<>()).add(p);
        return p;
    }

    public void group(int id, String name, String... groupBrands) {
        groups.put(id, name);
        for (String b : groupBrands) {
            GroupLink l = new GroupLink();
            l.setGroupId(id);
            l.setBrand(b);
            brands.add(l);
        }
    }

    @Override
    public List<AppSchedule> list(int customerId, int configurationId) {
        List<AppSchedule> out = new ArrayList<>();
        for (AppSchedule s : schedules.values()) if (s.getConfigurationId() == configurationId) out.add(s);
        return out;
    }

    @Override
    public AppSchedule get(int id, int customerId) {
        return schedules.get(id);
    }

    @Override
    public List<AppSchedule> listEnabled() {
        List<AppSchedule> out = new ArrayList<>();
        for (AppSchedule s : schedules.values()) if (s.isEnabled()) out.add(s);
        return out;
    }

    @Override
    public void insert(AppSchedule s) {
        s.setId(seq++);
        schedules.put(s.getId(), s);
    }

    @Override
    public int update(AppSchedule s) {
        return schedules.containsKey(s.getId()) ? 1 : 0;
    }

    @Override
    public int delete(int id, int customerId) {
        return schedules.remove(id) != null ? 1 : 0;
    }

    @Override
    public int claimSlot(int id, String slot) {
        AppSchedule s = schedules.get(id);
        if (s == null || !s.isEnabled() || (s.getLastSlot() != null && s.getLastSlot().compareTo(slot) >= 0)) return 0;
        s.setLastSlot(slot);
        return 1;
    }

    @Override
    public int countConfiguration(int customerId, int id) {
        return id == 4 || id == 5 ? 1 : 0;
    }

    @Override
    public String groupName(int customerId, int id) {
        return groups.get(id);
    }

    @Override
    public List<GroupLink> groupBrands(int customerId) {
        return brands;
    }

    @Override
    public List<SchedulePhone> phones(int customerId, int configurationId) {
        return phones.containsKey(configurationId) ? phones.get(configurationId) : new ArrayList<SchedulePhone>();
    }
}
