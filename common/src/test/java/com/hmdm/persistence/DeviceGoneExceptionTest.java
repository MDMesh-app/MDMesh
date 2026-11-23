/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.persistence;

import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * "Device gone" is read from the error's constraint field, so a server whose messages are translated (its quote marks
 * too) is recognised as an English one is. The errors are built as pgjdbc builds them from the server's fields
 * (S severity, C SQL state, M message, n constraint). A real server's error, with its fields, is covered by
 * DeviceHistoryOnDeleteTest.
 */
public class DeviceGoneExceptionTest {

    /** As MyBatis hands it to a DAO: the driver's exception inside its own. */
    private static RuntimeException error(String sqlState, String message, String constraint) {
        String fields = "SERREUR\0VERROR\0C" + sqlState + "\0M" + message + "\0"
                + (constraint == null ? "" : "n" + constraint + "\0");
        return new PersistenceException(new PSQLException(new ServerErrorMessage(fields)));
    }

    @Test
    public void aHistoryKeyIsRecognisedInAnyLanguage() {
        assertTrue(DeviceGoneException.isHistoryKeyViolation(error("23503",
                "une instruction insert ou update sur la table « device_event » viole la contrainte de clé étrangère "
                        + "« device_event_device_fk »", "device_event_device_fk")));
        assertTrue(DeviceGoneException.isHistoryKeyViolation(error("23503",
                "Einfügen oder Aktualisieren in Tabelle »agentcommand« verletzt Fremdschlüssel-Constraint "
                        + "»agentcommand_device_fk«", "agentcommand_device_fk")));
        for (String key : DeviceGoneException.HISTORY_KEYS) {
            assertTrue(key, DeviceGoneException.isHistoryKeyViolation(error("23503", "x", key)));
        }
    }

    @Test
    public void otherErrorsAreNotDeviceGone() {
        // Another foreign key, even one whose name ends the same way.
        assertFalse(DeviceGoneException.isHistoryKeyViolation(error("23503", "x", "fk_customer_3")));
        assertFalse(DeviceGoneException.isHistoryKeyViolation(error("23503", "x", "plugin_device_fk")));
        // The name in the message only, no constraint field.
        assertFalse(DeviceGoneException.isHistoryKeyViolation(error("23503",
                "violates foreign key constraint \"device_event_device_fk\"", null)));
        // Another error on a history key's table.
        assertFalse(DeviceGoneException.isHistoryKeyViolation(error("23505", "x", "device_event_device_fk")));
        // Not from the driver.
        assertFalse(DeviceGoneException.isHistoryKeyViolation(
                new RuntimeException(new SQLException("device_event_device_fk", "23503"))));
        assertFalse(DeviceGoneException.isHistoryKeyViolation(new IllegalStateException()));
    }

    @Test
    public void guardTurnsOnlyAHistoryKeyIntoDeviceGone() {
        try {
            DeviceGoneException.guard("n1", () -> {
                throw error("23503", "x", "device_location_device_fk");
            });
            fail("not turned into DeviceGoneException");
        } catch (DeviceGoneException e) {
            assertEquals("n1", e.deviceNumber);
        }
        RuntimeException other = error("23503", "x", "fk_customer_3");
        try {
            DeviceGoneException.guard("n1", () -> {
                throw other;
            });
            fail("swallowed");
        } catch (RuntimeException e) {
            assertTrue("passed through as it was", e == other);
        }
    }
}
