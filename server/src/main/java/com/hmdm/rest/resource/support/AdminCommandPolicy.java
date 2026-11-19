package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import com.hmdm.util.DesiredConfigBuilder;

import java.util.Locale;
import java.util.Map;

/**
 * <p>Which commands an admin may queue, and for which devices: the rules every admin route that queues for a device
 * applies (AgentAdminResource: a command, a bulk command, Install configuration apps, Retry apply), whatever the
 * console shows. Types are matched trimmed and in any case, so a mixed-case type can't slip past a rule.</p>
 *
 * <ul>
 *   <li>A device marked wiped takes no command of any type ({@link #refusalFor}).</li>
 *   <li>Some types never go out in bulk ({@link #BULK_FORBIDDEN_TYPES}): one device at a time, from its page.</li>
 *   <li>{@code config.apply} is the server's alone ({@link #isServerOwned}).</li>
 * </ul>
 */
public final class AdminCommandPolicy {

    /** The answer for a device marked wiped. */
    public static final String WIPED = "error.agent.command.wiped";
    /** The answer for a type that never goes out in bulk. */
    public static final String BULK_FORBIDDEN = "error.agent.command.bulkForbidden";
    /** The answer for a type only the server queues. */
    public static final String SERVER_OWNED = "error.agent.command.serverOwned";

    /**
     * Types never sent in bulk, lowercase, each with what it destroys: a slip in a selection would do it fleet-wide.
     * The console mirrors the keys (web/src/api/commands.ts).
     */
    static final Map<String, String> BULK_FORBIDDEN_TYPES = Map.of(
            "device.wipe", "erases the device",
            "device.passcodereset", "replaces the device's passcode",
            "app.uninstall", "removes an app and its data",
            // Reserved: no agent handles it yet. Refused in bulk before one does.
            "app.sethidden", "takes an app away from the user");

    private AdminCommandPolicy() {
    }

    /**
     * Why nothing may be queued for {@code device}, as the error key, or null when commands may be. A device marked
     * wiped (devices.wipedAt, the console's "Wiped") erased itself when its factory reset was delivered, so nothing
     * queued for it would ever run: every type is refused. The mark is lifted when the wipe reports failed or the
     * device checks in after it (AgentResource), and a wiped device that enrolls again is a new device, so a refusal
     * never outlives a wipe that happened. Rollouts leave wiped devices out in their query (RolloutMapper).
     */
    public static String refusalFor(Device device) {
        return device.getWipedAt() != null ? WIPED : null;
    }

    /** Whether {@code type} must never be queued for many devices at once. */
    public static boolean isBulkForbidden(String type) {
        return BULK_FORBIDDEN_TYPES.containsKey(normalize(type));
    }

    /**
     * {@code config.apply} is queued only by the server, from the device's configuration (ConfigReconciler), and at
     * most one is open per device: an admin one is refused before anything is queued.
     */
    public static boolean isServerOwned(String type) {
        return DesiredConfigBuilder.COMMAND_TYPE.toLowerCase(Locale.ROOT).equals(normalize(type));
    }

    private static String normalize(String type) {
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
