package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Notifications and the launcher icon. Channels keep their ids and importance (Android ignores a new importance on
 * an existing channel, so changing it would only make new and updated devices differ); status bar icons are
 * monochrome (Android draws only their alpha), and the launcher icon has a themed-icon layer.
 */
class NotificationIdentityTest {

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    private fun read(path: String): String = File(root, path).also { assertTrue("missing $it", it.isFile) }.readText()

    private val service by lazy { read("app/src/main/kotlin/com/mdmesh/agent/service/CheckInService.kt") }
    private val alerts by lazy { read("core/src/main/kotlin/com/mdmesh/core/action/AlertNotifier.kt") }

    @Test fun `channels keep their importance and names`() {
        assertTrue(service.contains("NotificationManager.IMPORTANCE_LOW"))
        assertTrue(alerts.contains("NotificationManager.IMPORTANCE_HIGH"))
        assertEquals("Device management", StringsXml["checkin_notification_channel"])
        assertEquals("Device management active", StringsXml["checkin_notification_title"])
        val core = read("core/src/main/res/values/strings.xml")
        assertTrue(core.contains("<string name=\"alert_notification_channel\">MDMesh alerts</string>"))
        assertTrue(alerts.contains("R.string.alert_notification_channel"))
    }

    @Test fun `status bar icons are our monochrome ones, with an accent`() {
        assertTrue(service.contains(".setSmallIcon(R.drawable.ic_stat_mdmesh)"))
        assertTrue(service.contains(".setColor("))
        assertTrue(alerts.contains(".setSmallIcon(R.drawable.ic_stat_alert)"))
        assertTrue(alerts.contains(".setColor("))
        val icons = listOf(
            "app/src/main/res/drawable/ic_stat_mdmesh.xml",
            "core/src/main/res/drawable/ic_stat_alert.xml",
        )
        for (icon in icons) {
            val colours = Regex("android:(?:fill|stroke)Color=\"([^\"]+)\"").findAll(read(icon))
                .map { it.groupValues[1] }.toSet()
            assertEquals("$icon colours", setOf("#FFFFFFFF"), colours)
        }
    }

    @Test fun `launcher icon is the white mark on v2 ink, with a themed-icon layer`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val adaptive = read("app/src/main/res/mipmap-anydpi-v26/$name.xml")
            assertTrue(name, adaptive.contains("<background android:drawable=\"@color/ic_launcher_background\" />"))
            assertTrue(name, adaptive.contains("<monochrome android:drawable=\"@drawable/ic_launcher_foreground\" />"))
        }
        assertTrue(read("app/src/main/res/values/colors.xml").contains(">#15181D</color>"))
        val mark = Regex("android:fillColor=\"([^\"]+)\"")
            .findAll(read("app/src/main/res/drawable/ic_launcher_foreground.xml")).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("#FFFFFF"), mark)
    }
}
