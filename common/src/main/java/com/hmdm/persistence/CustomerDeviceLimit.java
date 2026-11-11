/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.persistence;

import java.util.function.LongSupplier;

/**
 * <p>The one rule every path that adds a device follows for a customer's device limit (agent enrollment, a device added
 * in the console, on-demand creation): count and add one at a time per customer, so concurrent adds through any of
 * them can't together pass the limit.</p>
 *
 * <p>The callers run it inside the transaction that inserts the device. {@link #LOCK_SQL} takes a transaction-scoped
 * advisory lock on the customer: no row is locked, so other inserts that reference the customer and sign-ins (which
 * update it) never wait for it. It is taken only when a positive limit applies.</p>
 */
public final class CustomerDeviceLimit {

    /** Serialises device counting for customer {@code #{customerId}} until the transaction ends (namespace "MDME"). */
    public static final String LOCK_SQL =
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(1296321861, #{customerId})) AS locked";

    private CustomerDeviceLimit() {
    }

    /**
     * Whether one more device fits under {@code limit}: 0 means none, a negative limit blocks (as the console has it),
     * and a positive one is checked under the customer's lock ({@code lock}), then {@code count}ed.
     */
    public static boolean roomForOne(int limit, Runnable lock, LongSupplier count) {
        if (limit == 0) {
            return true;
        }
        if (limit < 0) {
            return false;
        }
        lock.run();
        return count.getAsLong() < limit;
    }
}
