package com.mdmesh.core.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.core.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #220 nearby: a lost `mdm_server` file (Android reads a corrupt preferences file as empty) must not send an enrolled
 * device, and its secret, to the baked default host. The address is kept in a mirror file as well.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerConfigStoreRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val primary = context.getSharedPreferences("mdm_server", Context.MODE_PRIVATE)
    private val mirror = context.getSharedPreferences("mdm_server_mirror", Context.MODE_PRIVATE)
    private val provisioned = "https://mdm.provisioned.test"

    @Test
    fun `a lost server file is restored from its mirror, not replaced by the baked host`() {
        ServerConfigStore(context).save(provisioned)
        primary.edit().clear().commit() // what a corrupt file reads as

        assertEquals(provisioned, ServerConfigStore(context).baseUrl())
        assertNotEquals(provisioned, BuildConfig.MDM_BASE_URL.trimEnd('/'))
        // On disk, not only in memory: an empty commit waits for the restore's background write, then the file is read.
        primary.edit().commit()
        val onDisk = File(context.dataDir, "shared_prefs/mdm_server.xml").readText()
        assertTrue("restored on disk: $onDisk", provisioned in onDisk)
    }

    @Test
    fun `with the server file lost, an invalid address from provisioning can't replace the mirrored good one`() {
        ServerConfigStore(context).save(provisioned)
        primary.edit().clear().commit()

        ServerConfigStore(context).save("not an address")

        assertEquals(provisioned, ServerConfigStore(context).baseUrl())
        assertEquals(provisioned, mirror.getString("base_url", null))
    }

    @Test
    fun `an install from before the mirror seeds it on its first read`() {
        primary.edit().putString("base_url", provisioned).putInt("base_url_rules", 1).commit()

        assertEquals(provisioned, ServerConfigStore(context).baseUrl())
        primary.edit().clear().commit()
        assertEquals(provisioned, ServerConfigStore(context).baseUrl())
    }

    @Test
    fun `a legacy address keeps its legacy meaning through the mirror`() {
        primary.edit().putString("base_url", "not an address").commit() // saved by an agent before ServerUrls
        ServerConfigStore(context).baseUrl()
        primary.edit().clear().commit()
        assertEquals(BuildConfig.MDM_BASE_URL.trimEnd('/'), ServerConfigStore(context).baseUrl())
        assertEquals("not an address", mirror.getString("base_url", null))
    }
}
