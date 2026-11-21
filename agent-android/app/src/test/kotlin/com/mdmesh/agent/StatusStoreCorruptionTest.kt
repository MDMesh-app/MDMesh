package com.mdmesh.agent

import android.app.Application
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.agent.stress.StoreCorruption
import com.mdmesh.core.store.AgentDataStores
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #220 on the status screen: its sources (device id, kiosk state, configuration) are read independently, so a store
 * the storage corrupted is recovered and one it can't read at all holds back nothing else; neither crashes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusStoreCorruptionTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val storage by lazy { StoreCorruption(app) }

    /** Opens the status screen and waits for it to show [expected] as the Device ID (read from storage). */
    private fun assertShowsDeviceId(expected: String) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Without the zero-width breaks the screen puts in for wrapping.
        fun shown() = activity.findViewById<TextView>(R.id.status_device_id).text.toString().replace("\u200B", "")
        storage.eventually("the Device ID $expected, showing \"${shown()}\"") { shown() == expected }
        assertEquals(expected, shown())
    }

    @Test fun `corrupt identity and kiosk stores are recovered and the device id shows`() {
        storage.corruptIdentity(mirrorId = "dev_7f3a")
        storage.corruptKiosk(mirror = null)
        assertShowsDeviceId("dev_7f3a")
        val both = listOf("mdm_identity from mirror", "mdm_kiosk from empty")
        storage.eventually("both recoveries") { storage.recoveries().sortedBy { it } == both }
    }

    @Test fun `an unreadable kiosk store does not hold back the device id`() {
        runBlocking { AgentDataStores.stores(app).deviceIdStore().saveCredentials("dev_9", "s") }
        storage.unreadable(AgentDataStores.KIOSK)
        assertShowsDeviceId("dev_9")
    }
}
