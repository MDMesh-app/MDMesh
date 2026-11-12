package com.mdmesh.agent

import com.mdmesh.core.sync.CredentialsLost
import com.mdmesh.core.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdLabelTest {

    @Test
    fun `lost credentials show under the id, whatever the last check-in reported`() {
        // As production shows it: recovery gave up, then the next check-in was refused and replaced the last error.
        val later = SyncStatus.Failure("error.agent.unauthorized", 200L)

        val shown = DeviceIdLabel.of("dev-1", CredentialsLost.NO_SUCCESS, lastSuccessAtMs = null, lastError = later)

        assertEquals(DeviceIdLabel.Status.Enrolled("dev-1", credentialsLost = true), shown)
    }

    @Test
    fun `a check-in that succeeded since the mark clears the warning at once`() {
        val first = DeviceIdLabel.of("dev-1", CredentialsLost.NO_SUCCESS, lastSuccessAtMs = 150L, lastError = null)
        assertEquals(DeviceIdLabel.Status.Enrolled("dev-1", credentialsLost = false), first)
        val later = DeviceIdLabel.of("dev-1", 500L, lastSuccessAtMs = 600L, lastError = null)
        assertEquals(DeviceIdLabel.Status.Enrolled("dev-1", credentialsLost = false), later)
    }

    @Test
    fun `a success stamped by a clock pulled back since the mark still clears it`() {
        val shown = DeviceIdLabel.of("dev-1", 500L, lastSuccessAtMs = 1L, lastError = null)
        assertEquals(DeviceIdLabel.Status.Enrolled("dev-1", credentialsLost = false), shown)
    }

    @Test
    fun `a success from before the mark, stamped by a clock that ran ahead, doesn't hide the loss`() {
        // The success at "500" happened before the credentials were refused; the mark remembers it as the last one.
        val shown = DeviceIdLabel.of("dev-1", 500L, lastSuccessAtMs = 500L, lastError = null)
        assertEquals(DeviceIdLabel.Status.Enrolled("dev-1", credentialsLost = true), shown)
    }

    @Test
    fun `an enrolled device with working credentials shows its id only`() {
        assertEquals(
            DeviceIdLabel.Status.Enrolled("dev-1", credentialsLost = false),
            DeviceIdLabel.of("dev-1", credentialsLostMark = null, lastSuccessAtMs = 10L, lastError = null),
        )
    }

    @Test
    fun `before enrollment it says so, with the last error when there is one`() {
        assertEquals(DeviceIdLabel.Status.Enrolling(null), DeviceIdLabel.of(null, null, null, null))
        val failure = SyncStatus.Failure("no code", 1L)
        assertEquals(DeviceIdLabel.Status.Enrolling(failure), DeviceIdLabel.of("", null, null, failure))
    }

    /** The colours the status screen really draws the warning in: warning ink on the card, light and dark. */
    @Test
    fun `the warning colour reads on the card, light and dark`() {
        val res = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "app/src/main/res") }.first { it.isDirectory }
        for (dir in listOf("values", "values-night")) {
            val colours = Regex("""<color name="(\w+)">#([0-9A-Fa-f]{6})</color>""")
                .findAll(java.io.File(res, "$dir/colors_v2.xml").readText())
                .associate { it.groupValues[1] to (0xFF000000L or it.groupValues[2].toLong(16)).toInt() }
            for (card in listOf("mdm_surface", "mdm_surface_2")) {
                // WCAG 2.x contrast for body text: at least 4.5:1.
                val ratio = Wcag.contrast(colours.getValue("mdm_warn_ink"), colours.getValue(card))
                assertTrue("$dir warn ink on $card: $ratio", ratio >= 4.5)
            }
        }
    }
}
