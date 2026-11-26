package com.mdmesh.policies.rest.json;

import java.util.ArrayList;
import java.util.List;

/**
 * A bulk action from the console. {@code action}:
 * <ul>
 *   <li>{@code reapply}: wake the phones so they check in and apply their policy (no command);</li>
 *   <li>{@code scan}: an app scan on each phone;</li>
 *   <li>{@code command}: one of the device page's remote actions ({@code command.type}, allowlisted on the server);</li>
 *   <li>{@code install}: the phones' policy apps, as core's Install config apps builds them;</li>
 *   <li>{@code remove}: uninstall {@code removals[].packages} on each phone, with an optional alert.</li>
 * </ul>
 * Phones are internal device ids, as core's bulk endpoint takes them.
 */
public class JobRequest {
    public String action;
    public String label;
    public List<Integer> deviceIds = new ArrayList<>();
    public Command command;
    public List<Removal> removals = new ArrayList<>();

    public static class Command {
        public String type;
        /** The command's payload as a JSON string, as core's queue endpoint takes it. */
        public String payload;
    }

    public static class Removal {
        public Integer deviceId;
        public List<String> packages = new ArrayList<>();
        public String alertTitle;
        public String alertBody;
    }
}
