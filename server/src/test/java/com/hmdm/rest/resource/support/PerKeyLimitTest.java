/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PerKeyLimitTest {

    @Test
    public void at_most_n_at_once_per_key_and_other_keys_are_independent() {
        PerKeyLimit<Integer> gate = new PerKeyLimit<>(2);
        assertTrue(gate.tryEnter(1));
        assertTrue(gate.tryEnter(1));
        assertFalse("a third waiter on the same code is turned away", gate.tryEnter(1));
        assertTrue("another code is not affected", gate.tryEnter(2));

        gate.exit(1);
        assertTrue("room again once one leaves", gate.tryEnter(1));
    }

    @Test
    public void an_idle_key_is_forgotten() {
        PerKeyLimit<Integer> gate = new PerKeyLimit<>(1);
        assertTrue(gate.tryEnter(7));
        gate.exit(7);
        assertEquals(0, gate.trackedKeys());
    }

    @Test
    public void concurrent_enter_and_exit_keep_the_count_exact() throws Exception {
        PerKeyLimit<Integer> gate = new PerKeyLimit<>(3);
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    if (gate.tryEnter(1)) gate.exit(1);
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        assertEquals(0, gate.trackedKeys());
        assertTrue(gate.tryEnter(1));
        assertTrue(gate.tryEnter(1));
        assertTrue(gate.tryEnter(1));
        assertFalse(gate.tryEnter(1));
    }
}
