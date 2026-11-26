package com.mdmesh.policies.service;

import com.mdmesh.policies.persistence.domain.AutomationSettings;
import com.mdmesh.policies.persistence.domain.FreshScan;
import com.mdmesh.policies.persistence.domain.JobPhone;
import com.mdmesh.policies.persistence.domain.KeyValue;
import com.mdmesh.policies.persistence.mapper.AutomationMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The automation's data in memory, behaving as the SQL in AutomationMapper does. */
public class FakeAutomationMapper implements AutomationMapper {
    public final Map<Integer, AutomationSettings> settings = new HashMap<>();
    /** The customer's phones by id (customer 1). */
    public final TreeMap<Integer, JobPhone> phones = new TreeMap<>();
    /** Phones that got a scan recently (the sweep skips them). */
    public final Set<String> recentlyScanned = new HashSet<>();
    public final Set<String> everScanned = new HashSet<>();
    public final List<FreshScan> fresh = new ArrayList<>();
    public final List<KeyValue> policies = new ArrayList<>();
    public final List<KeyValue> allowed = new ArrayList<>();
    public final List<KeyValue> uninstalls = new ArrayList<>();
    public final List<Integer> autoCustomers = new ArrayList<>();

    public void phone(int id) {
        JobPhone p = new JobPhone();
        p.setId(id);
        p.setNumber("P-" + id);
        p.setConfigurationId(4);
        p.setCustomerId(1);
        phones.put(id, p);
    }

    public static KeyValue kv(String k, String v) {
        KeyValue x = new KeyValue();
        x.setKey(k);
        x.setValue(v);
        return x;
    }

    @Override
    public AutomationSettings getSettings(int customerId) {
        return settings.get(customerId);
    }

    @Override
    public List<AutomationSettings> listScanning() {
        List<AutomationSettings> out = new ArrayList<>();
        for (AutomationSettings s : settings.values()) if (s.isNightlyScan()) out.add(s);
        return out;
    }

    @Override
    public void saveSettings(AutomationSettings s) {
        AutomationSettings cur = settings.get(s.getCustomerId());
        if (cur == null) {
            settings.put(s.getCustomerId(), s);
            return;
        }
        cur.setNightlyScan(s.isNightlyScan());
        cur.setWindowStart(s.getWindowStart());
        cur.setWindowEnd(s.getWindowEnd());
        cur.setTimeZone(s.getTimeZone());
    }

    @Override
    public void saveSweep(int customerId, String night, int cursor, Integer jobId) {
        AutomationSettings s = settings.get(customerId);
        s.setSweepNight(night);
        s.setSweepCursor(cursor);
        s.setSweepJobId(jobId);
    }

    @Override
    public void saveAutoRemoveSince(int customerId, long since) {
        AutomationSettings s = settings.get(customerId);
        if (s == null) {
            s = new AutomationSettings();
            s.setCustomerId(customerId);
            settings.put(customerId, s);
        }
        s.setAutoRemoveSince(since);
    }

    @Override
    public int countPhones(int customerId) {
        return phones.size();
    }

    @Override
    public int countPhonesUpTo(int customerId, int cursor) {
        return phones.headMap(cursor, true).size();
    }

    @Override
    public List<JobPhone> sweepBatch(int customerId, int cursor, long recentSince, int limit) {
        List<JobPhone> out = new ArrayList<>();
        for (JobPhone p : phones.tailMap(cursor, false).values()) {
            if (out.size() >= limit) break;
            if (!recentlyScanned.contains(p.getNumber())) out.add(p);
        }
        return out;
    }

    @Override
    public List<JobPhone> neverScanned(int customerId, long recentSince, int limit) {
        List<JobPhone> out = new ArrayList<>();
        for (JobPhone p : phones.values()) {
            if (out.size() >= limit) break;
            if (!everScanned.contains(p.getNumber()) && !recentlyScanned.contains(p.getNumber())) out.add(p);
        }
        return out;
    }

    @Override
    public List<Integer> listAutoRemoveCustomers() {
        return autoCustomers;
    }

    @Override
    public List<FreshScan> freshScans(int customerId, long since, long until) {
        List<FreshScan> out = new ArrayList<>();
        for (FreshScan f : fresh) if (f.getScannedAt() > since && f.getScannedAt() <= until) out.add(f);
        return out;
    }

    @Override
    public List<KeyValue> policyData(Collection<Integer> ids) {
        return policies;
    }

    @Override
    public List<KeyValue> allowedPackages(Collection<Integer> ids) {
        return allowed;
    }

    @Override
    public List<KeyValue> openUninstalls(Collection<String> numbers) {
        return uninstalls;
    }
}
