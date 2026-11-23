package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The status screen's layout: nothing selectable (no way out of the kiosk), explicit copy buttons, even dividers. */
class StatusLayoutTest {

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    private val layout: Element by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(root, "app/src/main/res/layout/activity_status.xml")).documentElement
    }

    private fun all(): List<Element> {
        val nodes = layout.getElementsByTagName("*")
        return listOf(layout) + (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String) = getAttributeNS(ANDROID, name)

    private fun byId(id: String) = all().single { it.android("id") == "@+id/$id" }

    @Test fun `no text on the status screen is selectable`() {
        // The selection toolbar offers Share, Translate and smart "Open" actions, which start other apps' activities
        // inside the locked kiosk task. Copy goes through explicit buttons instead.
        val selectable = all().filter { it.android("textIsSelectable") == "true" }.map { it.android("id") }
        assertEquals(emptyList<String>(), selectable)
        val selects = Regex("""setTextIsSelectable\(\s*true|isTextSelectable\s*=\s*true""")
        val code = File(root, "app/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .filter { selects.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), code)
    }

    @Test fun `device id and server have a labelled 48dp copy button`() {
        for (id in listOf("status_copy_device_id", "status_copy_server")) {
            val button = byId(id)
            assertEquals("$id is an ImageButton", "ImageButton", button.tagName)
            assertTrue("$id needs a content description", button.android("contentDescription").startsWith("@string/"))
            assertEquals("$id width", "@dimen/mdm_touch_target", button.android("layout_width"))
            assertEquals("$id height", "@dimen/mdm_touch_target", button.android("layout_height"))
        }
    }

    @Test fun `the copy confirmation adds no line, so rows keep their height`() {
        // "Copied" shows on the copy button itself (a check), not as a line that appears under the value.
        val extra = all().map { it.android("id") }.filter { it.endsWith("_done") }
        assertEquals(emptyList<String>(), extra)
    }

    @Test fun `every row of the device card is separated by a divider`() {
        // Children of the device card: callout, then rows (eyebrow + value), each preceded by one divider.
        val card = byId("status_device_card")
        val children = (0 until card.childNodes.length).map { card.childNodes.item(it) }.filterIsInstance<Element>()
        val dividers = children.count { it.getAttribute("style") == "@style/Widget.MdmAgent.Divider" }
        val rows = children.count { it.android("id").startsWith("@+id/status_row_") }
        assertEquals(3, rows)
        assertEquals("one divider before each row", rows, dividers)
        children.zipWithNext().filter { (_, b) -> b.android("id").startsWith("@+id/status_row_") }.forEach { (a, b) ->
            assertEquals("divider before ${b.android("id")}", "@style/Widget.MdmAgent.Divider", a.getAttribute("style"))
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
