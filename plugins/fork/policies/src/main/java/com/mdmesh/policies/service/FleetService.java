package com.mdmesh.policies.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.rest.json.agent.CommandHistoryView;
import com.hmdm.rest.json.agent.ConfigStatusView;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.DesiredConfigBuilder;
import com.mdmesh.policies.persistence.domain.FleetRow;
import com.mdmesh.policies.persistence.domain.OpenCommandRow;
import com.mdmesh.policies.persistence.mapper.FleetMapper;
import com.mdmesh.policies.rest.json.FleetView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the fleet answer from two queries ({@link FleetMapper}) plus one settings document per configuration, instead
 * of three requests per phone. Each phone's status is worked out exactly as core's per-phone configStatus does it
 * (through {@link CoreStatus}); a configuration core can't build a document for leaves its phones' status null, so the
 * console asks core for those phones, as before.
 */
@Singleton
public class FleetService {
    private static final Logger log = LoggerFactory.getLogger(FleetService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FleetMapper mapper;
    private final CoreStatus core;

    @Inject
    public FleetService(FleetMapper mapper, CoreStatus core) {
        this.mapper = mapper;
        this.core = core;
    }

    public FleetView fleet(int customerId, int userId, boolean superAdmin) {
        List<FleetRow> rows = mapper.listFleet(customerId, userId, superAdmin);
        List<OpenCommandRow> open = mapper.listOpenCommands(customerId, userId, superAdmin);
        return build(customerId, rows, open, System.currentTimeMillis());
    }

    /** The answer for these rows (separate from the queries, for tests). */
    FleetView build(int customerId, List<FleetRow> rows, List<OpenCommandRow> open, long now) {
        FleetView out = new FleetView();
        out.statusAvailable = core.available();
        out.serverTime = now;

        Set<String> scanPending = new HashSet<>();
        Map<String, Set<String>> removing = new HashMap<>();
        for (OpenCommandRow c : open) {
            if ("apps.scan".equals(c.getType())) {
                scanPending.add(c.getDeviceNumber());
            } else {
                String pkg = CommandHistoryView.subjectOf(c.getType(), c.getPayload());
                if (pkg != null) {
                    Set<String> set = removing.get(c.getDeviceNumber());
                    if (set == null) removing.put(c.getDeviceNumber(), set = new LinkedHashSet<>());
                    set.add(pkg);
                }
            }
        }

        // One document per configuration, not per phone. containsKey, not computeIfAbsent: a failed (null) document
        // is remembered too, so it isn't rebuilt (and logged) for every phone on that configuration.
        Map<Integer, DesiredConfig> docs = new HashMap<>();
        Set<Integer> failed = new HashSet<>();
        for (FleetRow r : rows) {
            FleetView.Phone p = new FleetView.Phone();
            p.id = r.getId() == null ? 0 : r.getId();
            p.number = r.getNumber();
            p.hardware = hardware(r.getTelemetry());
            p.scan = scan(r.getScanDetail(), r.getScannedAt());
            p.scanPending = scanPending.contains(r.getNumber());
            p.agentVersion = r.getAgentVersion();
            p.canLaunch = AgentCapabilityTokens.isAllowed(ScheduleService.LAUNCH_CAPABILITY, AgentCapabilityTokens.flatten(r.getCapabilities()));
            Set<String> rm = removing.get(r.getNumber());
            if (rm != null) p.removing.addAll(rm);

            if (out.statusAvailable) {
                Integer cfg = r.getConfigurationId();
                if (cfg != null && !docs.containsKey(cfg) && !failed.contains(cfg)) {
                    try {
                        docs.put(cfg, core.document(customerId, cfg));
                    } catch (RuntimeException e) {
                        log.warn("Policies plugin: no status document for configuration {}: {}", cfg, e.toString());
                        failed.add(cfg);
                    }
                }
                if (cfg == null || !failed.contains(cfg)) {
                    p.status = status(r, cfg == null ? null : docs.get(cfg));
                }
            }
            out.devices.add(p);
        }
        return out;
    }

    /** As core's configStatus: current vs applied revision, agent support, and the latest config.apply. */
    ConfigStatusView status(FleetRow r, DesiredConfig doc) {
        ConfigStatusView v = new ConfigStatusView();
        v.setConfigurationId(r.getConfigurationId());
        Set<String> tokens = AgentCapabilityTokens.flatten(r.getCapabilities());
        v.setCurrentRevision(core.revision(doc, tokens));
        v.setAppliedRevision(r.getAppliedRevision());
        v.setAppliedAt(r.getAppliedAt());
        v.setSupported(AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, tokens));
        v.setInSync(v.getCurrentRevision() != null && v.getCurrentRevision().equals(v.getAppliedRevision()));
        if (r.getApplyId() != null) {
            AgentCommand c = new AgentCommand();
            c.setId(r.getApplyId());
            c.setStatus(r.getApplyStatus());
            c.setDetail(r.getApplyDetail());
            c.setCreatedAt(r.getApplyCreatedAt());
            c.setCompletedAt(r.getApplyCompletedAt());
            v.setLastCommand(ConfigStatusView.LastCommand.from(c));
        }
        return v;
    }

    /** brand and manufacturer from the telemetry JSON; null when absent or unreadable. */
    static Map<String, String> hardware(String telemetry) {
        JsonNode hw = parse(telemetry).path("hardware");
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : new String[]{"brand", "manufacturer"}) {
            JsonNode v = hw.path(key);
            if (v.isTextual() && !v.asText().trim().isEmpty()) out.put(key, v.asText());
        }
        return out.isEmpty() ? null : out;
    }

    /** The scan's apps (package, name, system flag only); null when there is no scan or it is unreadable. */
    static FleetView.Scan scan(String detail, Long scannedAt) {
        JsonNode apps = parse(detail).path("apps");
        if (!apps.isArray()) return null;
        FleetView.Scan s = new FleetView.Scan();
        s.scannedAt = scannedAt;
        for (JsonNode a : apps) {
            JsonNode pkg = a.path("pkg");
            if (!pkg.isTextual() || pkg.asText().isEmpty()) continue;
            FleetView.App app = new FleetView.App();
            app.pkg = pkg.asText();
            if (a.path("label").isTextual()) app.label = a.path("label").asText();
            if (a.path("system").asBoolean(false)) app.system = Boolean.TRUE;
            s.apps.add(app);
        }
        return s;
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isEmpty()) return JSON.missingNode();
        try {
            JsonNode n = JSON.readTree(json);
            return n == null ? JSON.missingNode() : n;
        } catch (Exception e) {
            return JSON.missingNode();
        }
    }
}
