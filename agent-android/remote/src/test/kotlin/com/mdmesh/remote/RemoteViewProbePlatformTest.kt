package com.mdmesh.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteViewProbePlatformTest {

    @Test
    fun `api 28 uses the compatibility foreground service path`() {
        assertFalse(RemoteViewProbePlatform.usesTypedForegroundService(28))
    }

    @Test
    fun `api 29 and newer use the typed media projection foreground service`() {
        assertTrue(RemoteViewProbePlatform.usesTypedForegroundService(29))
        assertTrue(RemoteViewProbePlatform.usesTypedForegroundService(35))
    }
}
