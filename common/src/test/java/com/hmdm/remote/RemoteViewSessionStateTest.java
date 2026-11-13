package com.hmdm.remote;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteViewSessionStateTest {
    @Test public void accepts_only_ordered_progression_and_terminal_exit() {
        assertTrue(RemoteViewSessionState.REQUESTED.permits(RemoteViewSessionState.AWAITING_DEVICE));
        assertFalse(RemoteViewSessionState.REQUESTED.permits(RemoteViewSessionState.CONNECTED));
        assertTrue(RemoteViewSessionState.AWAITING_CONSENT.permits(RemoteViewSessionState.DENIED));
        assertTrue(RemoteViewSessionState.CONNECTED.permits(RemoteViewSessionState.STOPPED));
        assertFalse(RemoteViewSessionState.STOPPED.permits(RemoteViewSessionState.CONNECTED));
    }
}
