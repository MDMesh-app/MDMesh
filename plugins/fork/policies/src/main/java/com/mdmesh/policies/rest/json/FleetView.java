package com.mdmesh.policies.rest.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hmdm.rest.json.agent.ConfigStatusView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The fleet answer: every phone the user may access, with what the Policies page shows for it. */
public class FleetView {
    /** Whether core's status logic was reachable. When false every {@code status} is null. */
    public boolean statusAvailable;
    public long serverTime;
    public List<Phone> devices = new ArrayList<>();

    /** One phone. A null {@code status} means: ask core's per-phone status endpoint for it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Phone {
        public int id;
        public String number;
        /** {@code brand} and {@code manufacturer} from the agent's telemetry, when it sent them. */
        public Map<String, String> hardware;
        /** The same shape as core's {@code /private/agent/v1/devices/{number}/configStatus}. */
        public ConfigStatusView status;
        /** The latest finished app scan; null when the phone was never scanned. */
        public Scan scan;
        public boolean scanPending;
        /** Packages with an uninstall queued or on its way. */
        public List<String> removing = new ArrayList<>();
        /** The phone's agent reports {@code app.launch}: it can open an app on request. */
        public boolean canLaunch;
        /** The agent version the phone last reported. */
        public String agentVersion;
    }

    public static class Scan {
        public List<App> apps = new ArrayList<>();
        public Long scannedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class App {
        public String pkg;
        public String label;
        public Boolean system;
    }
}
