/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A row of a device's agent history (command, state, event, location) was refused because the device no longer
 * exists: it was deleted while the request that writes it was running. The history tables reference
 * devices(number) by foreign keys named {@code <table>_device_fk}, so such a write fails with SQL state 23503 instead of
 * leaving a row no device owns. Callers answer as for an unknown device.
 */
public final class DeviceGoneException extends RuntimeException {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /** The history tables' foreign keys to devices(number), as PostgreSQL names them (unquoted, so lower case). */
    static final Set<String> HISTORY_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "agentcommand_device_fk", "device_state_device_fk", "device_event_device_fk", "device_location_device_fk",
            "agentrolloutcanary_device_fk")));

    public final String deviceNumber;

    public DeviceGoneException(String deviceNumber, Throwable cause) {
        super("device " + deviceNumber + " no longer exists", cause);
        this.deviceNumber = deviceNumber;
    }

    /**
     * True when {@code e} is, or is caused by, a history foreign key refusing a row for a device that is gone. Read from
     * the error's constraint field, which no server language changes (the message text and its quote marks do).
     */
    static boolean isHistoryKeyViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PSQLException && FOREIGN_KEY_VIOLATION.equals(((PSQLException) t).getSQLState())) {
                ServerErrorMessage server = ((PSQLException) t).getServerErrorMessage();
                return server != null && server.getConstraint() != null
                        && HISTORY_KEYS.contains(server.getConstraint().toLowerCase(Locale.ROOT));
            }
        }
        return false;
    }

    /** Runs a history write for {@code deviceNumber}, turning a history foreign key violation into this exception. */
    static <T> T guard(String deviceNumber, Supplier<T> write) {
        try {
            return write.get();
        } catch (RuntimeException e) {
            if (isHistoryKeyViolation(e)) {
                throw new DeviceGoneException(deviceNumber, e);
            }
            throw e;
        }
    }

    static void guard(String deviceNumber, Runnable write) {
        guard(deviceNumber, () -> {
            write.run();
            return null;
        });
    }
}
