package com.mdmesh.policies.rest.json;

import java.util.LinkedHashMap;
import java.util.Map;

/** A job and its progress. */
public class JobView {
    public int id;
    public String action;
    public String label;
    public long createdAt;
    public boolean cancelled;
    /** Phones in the job. */
    public int phones;
    /** Phones by wake state: queued, woken, offline (gets its commands at its next check-in), cancelled. */
    public Map<String, Integer> wake = new LinkedHashMap<>();
    /** Commands by core status: pending, delivered, accepted, done, failed, unsupported, expired. */
    public Map<String, Integer> commands = new LinkedHashMap<>();
    /**
     * sending: phones still waiting for their wake; running: phones working on commands; waiting: only offline phones'
     * commands are left (they run when those phones connect); done; cancelled.
     */
    public String phase;
}
