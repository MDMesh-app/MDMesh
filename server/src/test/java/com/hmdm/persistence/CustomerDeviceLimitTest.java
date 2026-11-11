/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The device-limit rule every device-adding path shares. */
public class CustomerDeviceLimitTest {

    private final List<String> calls = new ArrayList<>();

    private boolean room(int limit, long count) {
        return CustomerDeviceLimit.roomForOne(limit, () -> calls.add("lock"), () -> {
            calls.add("count");
            return count;
        });
    }

    @Test
    public void no_limit_takes_no_lock() {
        assertTrue(room(0, 1_000));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void a_negative_limit_blocks_without_a_lock() {
        assertFalse(room(-1, 0));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void a_positive_limit_counts_under_the_lock() {
        assertTrue(room(3, 2));
        assertFalse(room(3, 3));
        assertEquals("the lock comes before each count", "lock,count,lock,count", String.join(",", calls));
    }

    @Test
    public void enrollment_and_console_or_on_demand_creation_take_the_same_lock() {
        String expected = CustomerDeviceLimit.LOCK_SQL.trim();
        assertEquals(expected, Fakes.sqlOf(AgentDeviceMapper.class, "lockCustomerEnrollments").trim());
        assertEquals(expected, Fakes.sqlOf(DeviceMapper.class, "lockCustomerDevices").trim());
        assertTrue(expected, expected.contains("pg_advisory_xact_lock(1296321861, #{customerId})"));
    }
}
