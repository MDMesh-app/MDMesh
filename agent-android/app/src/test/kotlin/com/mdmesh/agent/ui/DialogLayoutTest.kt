package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The dialog layout's line-up rules, from the resources: one 24dp inset for every row (title, message, body,
 * buttons), M3 spacing around the buttons, and 48dp touch targets. These are what the dialogs looked wrong without.
 */
class DialogLayoutTest {

    private val res: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        File(root, "app/src/main/res")
    }

    private fun xml(path: String): Element {
        val f = File(res, path)
        assertTrue("missing $f", f.isFile)
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(f).documentElement
    }

    private fun Element.all(): List<Element> {
        val out = mutableListOf(this)
        val kids = childNodes
        for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { out += it.all() }
        return out
    }

    private fun Element.byId(id: String): Element =
        all().single { it.getAttribute("android:id") == "@+id/$id" }

    private val dimens: Map<String, String> by lazy {
        val nodes = xml("values/dimens_v2.xml").all().filter { it.tagName == "dimen" }
        nodes.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun dp(ref: String): Int {
        val value = if (ref.startsWith("@dimen/")) dimens.getValue(ref.removePrefix("@dimen/")) else ref
        return value.removeSuffix("dp").toInt()
    }

    private val inset = "@dimen/mdm_dialog_padding"

    @Test fun `title, message and body share the content's 24dp inset`() {
        val layout = xml("layout/mdm_dialog.xml")
        val content = layout.byId("mdm_dialog_scroll").all()[1]
        assertEquals(inset, content.getAttribute("android:paddingStart"))
        assertEquals(inset, content.getAttribute("android:paddingEnd"))
        assertEquals(inset, content.getAttribute("android:paddingTop"))
        assertEquals(24, dp(inset))
        // Nothing inside adds its own side inset, so every row starts on the same edge.
        val sides = content.all().drop(1).filter { el ->
            listOf("paddingStart", "paddingLeft", "layout_marginStart", "layout_marginLeft")
                .any { el.hasAttribute("android:$it") }
        }.map { it.getAttribute("android:id") }
        assertEquals("rows with their own start inset", emptyList<String>(), sides)
        val body = xml("layout/dialog_exit_pin.xml")
        listOf("paddingStart", "paddingEnd", "paddingLeft", "paddingRight", "padding").forEach {
            assertTrue("exit PIN body sets $it", !body.hasAttribute("android:$it"))
        }
    }

    @Test fun `buttons end on the content's edge, 24dp from the body and the bottom`() {
        val bar = xml("layout/mdm_dialog.xml").byId("mdm_dialog_buttons")
        assertEquals(inset, bar.getAttribute("android:paddingEnd"))
        // The button shapes are inset inside their 48dp touch targets; the bar padding makes up the rest.
        val insets = listOf("mdm_btn_filled", "mdm_btn_text", "mdm_btn_danger", "mdm_btn_outlined").map {
            val d = xml("drawable/$it.xml")
            assertEquals(d.getAttribute("android:insetTop"), d.getAttribute("android:insetBottom"))
            dp(d.getAttribute("android:insetTop"))
        }.distinct()
        assertEquals("one button inset", 1, insets.size)
        assertEquals(dp(inset), dp(bar.getAttribute("android:paddingTop")) + insets.single())
        assertEquals(dp(inset), dp(bar.getAttribute("android:paddingBottom")) + insets.single())
    }

    @Test fun `every dialog control is a 48dp touch target`() {
        assertEquals(48, dp("@dimen/mdm_touch_target"))
        val styles = xml("values/styles.xml").all().filter { it.tagName == "style" }
        val button = styles.single { it.getAttribute("name") == "Widget.MdmAgent.Button" }
        val minHeight = button.all().single { it.getAttribute("name") == "android:minHeight" }.textContent.trim()
        assertEquals("@dimen/mdm_touch_target", minHeight)
        // The other button styles inherit it by name and don't lower it.
        styles.filter { it.getAttribute("name").startsWith("Widget.MdmAgent.Button.") }.forEach { s ->
            assertTrue(s.getAttribute("name"), s.all().none { it.getAttribute("name") == "android:minHeight" })
        }
        val buttons = xml("layout/mdm_dialog.xml").all().filter { it.tagName == "Button" }
        assertTrue(buttons.isNotEmpty())
        buttons.forEach {
            val style = it.getAttribute("style")
            assertTrue(it.getAttribute("android:id"), style.startsWith("@style/Widget.MdmAgent.Button"))
        }
        val toggle = xml("layout/dialog_exit_pin.xml").byId("exit_pin_toggle")
        assertEquals("@dimen/mdm_touch_target", toggle.getAttribute("android:layout_width"))
        assertEquals("@dimen/mdm_touch_target", toggle.getAttribute("android:layout_height"))
    }

    @Test fun `the PIN error starts where the typed text starts, and is announced`() {
        val body = xml("layout/dialog_exit_pin.xml")
        val field = body.byId("exit_pin_field")
        val error = body.byId("exit_pin_error")
        assertEquals(field.getAttribute("android:paddingStart"), error.getAttribute("android:paddingStart"))
        assertEquals("polite", error.getAttribute("android:accessibilityLiveRegion"))
        // The text never runs under the show/hide toggle.
        val toggle = body.byId("exit_pin_toggle")
        assertEquals(field.getAttribute("android:paddingEnd"), toggle.getAttribute("android:layout_width"))
    }

    @Test fun `the header icon centres on the title's cap height`() {
        // 18sp title at 1x: baseline 19, caps 13, icon 20 → top 3 (centre 13 = 19 - 6.5, rounded).
        assertEquals(3, MdmDialog.iconTopFor(baseline = 19, capHeight = 13, iconHeight = 20))
        for (baseline in 10..80) for (caps in 5..baseline) for (icon in 10..60 step 5) {
            val top = MdmDialog.iconTopFor(baseline, caps, icon)
            val centre = top + icon / 2.0
            assertTrue("$baseline/$caps/$icon", kotlin.math.abs(centre - (baseline - caps / 2.0)) <= 0.5)
        }
    }
}
