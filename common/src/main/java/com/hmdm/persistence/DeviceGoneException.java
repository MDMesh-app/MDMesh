/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import java.sql.SQLException;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A row of a device's agent history (command, state, event, location) was refused because the device no longer
 * exists: it was deleted while the request that writes it was running. The history tables reference
 * devices(number) by foreign keys named {@code <table>_device_fk}, so such a write fails with SQL state 23503 instead of
 * leaving a row no device owns. Callers answer as for an unknown device.
 */
public final class DeviceGoneException extends RuntimeException {

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String HISTORY_KEY_SUFFIX = "_device_fk\"";

    public final String deviceNumber;

    public DeviceGoneException(String deviceNumber, Throwable cause) {
        super("device " + deviceNumber + " no longer exists", cause);
        this.deviceNumber = deviceNumber;
    }

    /** True when {@code e} is, or is caused by, a history foreign key refusing a row for a device that is gone. */
    static boolean isHistoryKeyViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException && FOREIGN_KEY_VIOLATION.equals(((SQLException) t).getSQLState())) {
                // The constraint name is quoted in the server's message whatever its language.
                String message = t.getMessage();
                return message != null && message.toLowerCase(Locale.ROOT).contains(HISTORY_KEY_SUFFIX);
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
