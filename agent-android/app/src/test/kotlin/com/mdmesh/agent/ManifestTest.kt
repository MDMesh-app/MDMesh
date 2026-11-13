package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Manifest invariants the UI work must keep. */
class ManifestTest {

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    private val manifest: Element by lazy {
        val f = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("missing $f", f.isFile)
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(f).documentElement
    }

    private fun component(tag: String, name: String): Element {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .firstOrNull { it.getAttributeNS(ANDROID, "name") == name } ?: error("no <$tag> $name")
    }

    @Test fun `a system light-dark switch does not recreate the kiosk HOME`() {
        val flags = component("activity", ".KioskLauncherActivity").getAttributeNS(ANDROID, "configChanges")
        assertTrue("KioskLauncherActivity must handle uiMode itself, got '$flags'", "uiMode" in flags.split('|'))
    }

    /** A keyboard or barcode scanner connecting mid-entry must not recreate HOME (it would wipe a typed PIN). */
    @Test fun `a keyboard or scanner connecting does not recreate the kiosk HOME`() {
        val flags = component("activity", ".KioskLauncherActivity").getAttributeNS(ANDROID, "configChanges").split('|')
        for (change in listOf("keyboard", "keyboardHidden", "navigation")) assertTrue(change, change in flags)
    }

    @Test fun `rotation, window size, font size and display size do not recreate the kiosk HOME`() {
        // A recreate flashes the idle screen and replays the kiosk (reopening the pinned app); the launcher redraws
        // itself in onConfigurationChanged instead.
        val flags = component("activity", ".KioskLauncherActivity").getAttributeNS(ANDROID, "configChanges").split('|')
        val needed = listOf(
            "orientation", "screenSize", "smallestScreenSize", "screenLayout", "fontScale", "density",
            "keyboard", "keyboardHidden", "navigation",
        )
        assertEquals(emptyList<String>(), needed - flags.toSet())
    }

    @Test fun `the kiosk HOME keeps its launch and lock-task attributes`() {
        val kiosk = component("activity", ".KioskLauncherActivity")
        assertEquals("singleInstance", kiosk.getAttributeNS(ANDROID, "launchMode"))
        assertEquals("if_whitelisted", kiosk.getAttributeNS(ANDROID, "lockTaskMode"))
        val alias = component("activity-alias", ".KioskHomeAlias")
        assertEquals(".KioskLauncherActivity", alias.getAttributeNS(ANDROID, "targetActivity"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
