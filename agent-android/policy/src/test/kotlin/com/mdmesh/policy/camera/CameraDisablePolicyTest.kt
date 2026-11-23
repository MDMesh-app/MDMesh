package com.mdmesh.policy.camera

import com.mdmesh.policy.ReleasablePolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDisablePolicyTest {

    /** ConfigApplier only releases keys whose policy is releasable; Not managed after Block must restore the camera. */
    @Test
    fun `camera block is released when a configuration stops managing it`() {
        assertTrue(ReleasablePolicy::class.java.isAssignableFrom(CameraDisablePolicy::class.java))
    }
}
