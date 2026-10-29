/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KioskAlertLimitsTest {

    private static final long HOUR = 60L * 60L * 1000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final KioskAlertLimits limits = new KioskAlertLimits(now::get);

    private static Device device(int customer, String number) {
        Device d = new Device();
        d.setNumber(number);
        d.setCustomerId(customer);
        return d;
    }

    @Test
    public void oneAlertPerDevicePerHour() {
        assertTrue(limits.tryAcquire(device(1, "1234")));
        assertFalse(limits.tryAcquire(device(1, "1234")));
        now.addAndGet(HOUR);
        assertTrue(limits.tryAcquire(device(1, "1234")));
    }

    @Test
    public void atMostTenAlertsPerCustomerPerHour() {
        for (int i = 0; i < KioskAlertLimits.PER_CUSTOMER_PER_HOUR; i++) {
            assertTrue(limits.tryAcquire(device(1, "dev-" + i)));
        }
        assertFalse(limits.tryAcquire(device(1, "one-more")));
        // Another customer's alerts are not held up.
        assertTrue(limits.tryAcquire(device(2, "other")));
        now.addAndGet(HOUR);
        assertTrue(limits.tryAcquire(device(1, "one-more")));
    }

    @Test
    public void aDeviceHeldBackByItsCustomersCapCanAlertOnceThereIsRoom() {
        for (int i = 0; i < KioskAlertLimits.PER_CUSTOMER_PER_HOUR; i++) {
            limits.tryAcquire(device(1, "dev-" + i));
        }
        assertFalse(limits.tryAcquire(device(1, "late")));
        limits.release(device(1, "dev-0"));
        // "late" did not use up its own hour while it was refused.
        assertTrue(limits.tryAcquire(device(1, "late")));
    }

    @Test
    public void releaseGivesBothSlotsBack() {
        assertTrue(limits.tryAcquire(device(1, "1234")));
        limits.release(device(1, "1234"));
        assertTrue(limits.tryAcquire(device(1, "1234")));
    }
}
