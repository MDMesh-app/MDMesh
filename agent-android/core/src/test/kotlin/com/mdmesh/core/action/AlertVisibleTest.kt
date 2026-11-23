package com.mdmesh.core.action

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertVisibleTest {
    @Test fun `visible when notifications are on and the channel is not blocked`() {
        assertTrue(alertVisible(appEnabled = true, channelImportance = NotificationManager.IMPORTANCE_HIGH))
    }

    @Test fun `no channel below Android 8 is visible`() {
        assertTrue(alertVisible(appEnabled = true, channelImportance = null))
    }

    @Test fun `hidden when the alert channel is turned off`() {
        assertFalse(alertVisible(appEnabled = true, channelImportance = NotificationManager.IMPORTANCE_NONE))
    }

    @Test fun `hidden when app notifications are off or the permission is missing`() {
        assertFalse(alertVisible(appEnabled = false, channelImportance = NotificationManager.IMPORTANCE_HIGH))
    }
}
