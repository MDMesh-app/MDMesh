package com.mdmesh.agent.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guards read general categories from the device's own data (`android.icu`, as its iterator), not the host JVM's:
 * on Android 7 (Unicode 8), U+08D4 (a combining mark since Unicode 9) is unassigned, so a cut before it is allowed,
 * exactly as the device does it. Under the JVM's newer data the test would see a mark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class IcuGraphemes24Test {
    @Test fun categoriesComeFromTheDevicesUnicode() {
        assertTrue(1 in IcuGraphemes.ends("aࣔb").toList())
    }
}
