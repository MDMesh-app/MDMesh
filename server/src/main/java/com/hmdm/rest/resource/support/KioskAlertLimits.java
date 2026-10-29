/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import com.hmdm.util.WindowLimiter;

import java.util.function.LongSupplier;

/**
 * How many kiosk lockout alerts may go out: one per device per hour, and at most {@link #PER_CUSTOMER_PER_HOUR} per
 * customer per hour. The device limit stops one device from mailing on every lockout. The customer limit stops one
 * tenant with many devices from filling the shared mail queue (and using the server's mail server) at the expense of
 * the other tenants. In memory, per server process (see {@link WindowLimiter}).
 */
public final class KioskAlertLimits {

    static final long WINDOW_MS = 60L * 60L * 1000L;
    static final int PER_CUSTOMER_PER_HOUR = 10;

    private final WindowLimiter perDevice;
    private final WindowLimiter perCustomer;

    /** @param clock the current time in milliseconds (injectable for tests) */
    public KioskAlertLimits(LongSupplier clock) {
        this.perDevice = new WindowLimiter(clock, WINDOW_MS, 1);
        this.perCustomer = new WindowLimiter(clock, WINDOW_MS, PER_CUSTOMER_PER_HOUR);
    }

    /** @return true, using up the device's and its customer's slots, when an alert for this device may go out now. */
    public boolean tryAcquire(Device device) {
        if (!perDevice.tryAcquire(deviceKey(device))) {
            return false;
        }
        if (!perCustomer.tryAcquire(customerKey(device))) {
            // The device sent nothing, so it keeps its hour for when its customer has room again.
            perDevice.release(deviceKey(device));
            return false;
        }
        return true;
    }

    /** Gives both slots back, for an alert that was allowed but could not be queued. */
    public void release(Device device) {
        perDevice.release(deviceKey(device));
        perCustomer.release(customerKey(device));
    }

    /** Device numbers are unique across customers. */
    private static String deviceKey(Device device) {
        return device.getNumber();
    }

    private static String customerKey(Device device) {
        return String.valueOf(device.getCustomerId());
    }
}
