package com.mdmesh.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the on-device recovery action: it must restore only the saved kiosk
 * payload, not run the complete desired-state configuration path and undo an administrator's
 * temporary device changes.
 */
class MainActivityKioskStartTest {

    private fun source(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, "app/src/main/kotlin/com/mdmesh/agent/MainActivity.kt").readText()
    }

    private fun body(src: String, name: String): String {
        val start = Regex("\\bfun\\s+$name\\s*\\(").find(src)?.range?.first ?: error("no fun $name")
        var index = src.indexOf('{', start)
        var depth = 0
        while (true) {
            when (src[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(start, index + 1)
            }
            index++
        }
    }

    @Test
    fun `start configured kiosk uses the saved kiosk payload without applying configuration`() {
        val method = body(source(), "startConfiguredKiosk")
        assertTrue(method.contains("configState.load()?.kiosk"))
        assertTrue(method.contains("kioskApplier.enter(payload)"))
        assertFalse(method.contains("ConfigApplier"))
        assertFalse(method.contains(".apply("))
    }

    @Test
    fun `start button is available only for an unlocked managed device with a saved kiosk`() {
        val src = source()
        assertTrue(src.contains("text = \"Start configured kiosk\""))
        assertTrue(src.contains("Restores kiosk mode only. Other configuration settings are not reapplied."))
        assertTrue(src.contains("isDeviceOwner() && configState.load()?.kiosk != null && !isLocked()"))
    }

    @Test
    fun `successful local kiosk start is reported promptly`() {
        val method = body(source(), "startConfiguredKiosk")
        assertTrue(method.contains("events.record(\"configuredKioskStart\""))
        assertTrue(method.contains("CheckInWorker.scheduleNow(applicationContext)"))
    }
}
