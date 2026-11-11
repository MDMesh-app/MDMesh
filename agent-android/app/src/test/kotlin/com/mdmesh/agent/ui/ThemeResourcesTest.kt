package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The v2 theme in light (values/) and dark (values-night/): both define the same colours, and every text and UI
 * pair the theme draws keeps WCAG contrast in both.
 */
class ThemeResourcesTest {

    private val res: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        File(root, "app/src/main/res")
    }

    private fun items(file: File, tag: String): Map<String, String> {
        assertTrue("missing $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent.trim()
        }
    }

    private fun colours(dir: String): Map<String, Int> =
        items(File(res, "$dir/colors_v2.xml"), "color").mapValues { (name, hex) ->
            assertTrue("$dir $name: $hex", hex.matches(Regex("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")))
            val v = hex.removePrefix("#").toLong(16)
            (if (hex.length == 7) v or 0xFF000000 else v).toInt()
        }

    private val light by lazy { colours("values") }
    private val dark by lazy { colours("values-night") }

    @Test fun `light and dark define the same colours`() {
        assertEquals(light.keys.sorted(), dark.keys.sorted())
    }

    @Test fun `dark overrides only what light defines`() {
        val lightDimens = items(File(res, "values/dimens_v2.xml"), "dimen").keys +
            items(File(res, "values/dimens_v2.xml"), "item").keys
        val darkDimens = items(File(res, "values-night/dimens_v2.xml"), "dimen").keys +
            items(File(res, "values-night/dimens_v2.xml"), "item").keys
        assertTrue("night-only dimens: ${darkDimens - lightDimens}", lightDimens.containsAll(darkDimens))
        assertTrue("the dialog scrim differs per mode", "mdm_dim_amount" in darkDimens)
    }

    @Test fun `text pairs pass 4_5 to 1 in both modes`() {
        val pairs = listOf(
            "mdm_text" to "mdm_app",
            "mdm_text" to "mdm_surface",
            "mdm_text_2" to "mdm_surface", // dialog message
            "mdm_secondary" to "mdm_surface", // field hint
            "mdm_secondary" to "mdm_app",
            "mdm_on_ink" to "mdm_ink", // filled button label
            "mdm_ink" to "mdm_surface", // ghost and outlined button label
            "mdm_alert_ink" to "mdm_surface", // danger button label
            "mdm_disabled_ink" to "mdm_disabled_bg", // disabled filled button
            "mdm_disabled_ink" to "mdm_surface", // disabled ghost and danger label on the dialog
            "mdm_ok_ink" to "mdm_ok_soft", // kiosk "Active" pill
            "mdm_ok_ink" to "mdm_ok_wash", // managed callout
            "mdm_secondary" to "mdm_surface_2", // kiosk "Off" pill
            "mdm_warn_ink" to "mdm_warn_soft",
            "mdm_alert_ink" to "mdm_alert_soft",
        )
        assertPairs(pairs, Contrast.TEXT_MIN)
    }

    /**
     * "Inputs strong, buttons soft" (owner, console tokens.css): the edges that are the only way to see a control
     * (a text field's outline, an icon-only button's glyph: copy, show PIN) use the control border and reach 3:1.
     */
    @Test fun `inputs and icon-only buttons reach 3 to 1 in both modes`() {
        assertPairs(
            listOf(
                "mdm_control_border" to "mdm_surface",
                "mdm_control_border" to "mdm_app",
                "mdm_ink" to "mdm_surface", // focused field
            ),
            Contrast.UI_MIN,
        )
    }

    /**
     * Exempt from 3:1 (WCAG 1.4.11 needs it only where the edge is what identifies the control): card and divider
     * lines are decorative, and outlined and danger buttons are identified by their label (4.5:1, above), so their
     * outlines stay soft. Each edge is wired to its role here, so the rule can't drift drawable by drawable.
     */
    @Test fun `each outline uses the token for its role`() {
        fun stroke(drawable: String) = Regex("""<stroke[^>]*android:color="@color/(\w+)"""")
            .findAll(File(res, "drawable/$drawable.xml").readText()).map { it.groupValues[1] }.toSet()
        // Enabled: control border; focused: 2dp ink; disabled: soft (disabled controls are exempt).
        assertEquals(setOf("mdm_control_border", "mdm_ink", "mdm_border"), stroke("mdm_field_bg"))
        assertEquals(setOf("mdm_border_strong"), stroke("mdm_btn_outlined"))
        assertEquals(setOf("mdm_alert_line"), stroke("mdm_btn_danger"))
        assertEquals(setOf("mdm_border"), stroke("mdm_card_bg"))
        for (icon in listOf("ic_v2_eye", "ic_v2_eye_off")) {
            val colours = Regex("""strokeColor="@color/(\w+)"""").findAll(File(res, "drawable/$icon.xml").readText())
                .map { it.groupValues[1] }.toSet()
            assertEquals(icon, setOf("mdm_control_border"), colours)
        }
        val status = File(res, "layout/activity_status.xml").readText()
        assertEquals(2, Regex("""android:tint="@color/mdm_control_border"""").findAll(status).count())
    }

    /**
     * Readability beyond AA (owner, 2026-11-10): the small secondary text (eyebrows, subtitle, hint, pill) and the
     * status callout text reach 7:1 on the fills they sit on. The "Active" pill sits on the stronger ok soft fill,
     * so it is held to AA only (above).
     */
    @Test fun `secondary and status text reach 7 to 1 in both modes`() {
        assertPairs(
            listOf(
                "mdm_secondary" to "mdm_app",
                "mdm_secondary" to "mdm_surface",
                "mdm_secondary" to "mdm_surface_2",
                "mdm_ok_ink" to "mdm_ok_wash",
                "mdm_ok_ink" to "mdm_surface", // "Copied" check
                "mdm_warn_ink" to "mdm_warn_soft",
                "mdm_alert_ink" to "mdm_alert_soft",
                "mdm_alert_ink" to "mdm_surface", // PIN error line
            ),
            AAA_TEXT,
        )
    }

    @Test fun `disabled text stays readable in both modes`() {
        // Disabled controls are exempt from WCAG contrast, but "Returning…" is read: same floor as all text.
        assertPairs(listOf("mdm_disabled_ink" to "mdm_disabled_bg"), Contrast.TEXT_MIN)
    }

    @Test fun `focus ring passes 3 to 1 in both modes`() {
        assertPairs(
            listOf(
                "mdm_focus" to "mdm_surface",
                "mdm_focus" to "mdm_app",
                "mdm_on_ink" to "mdm_ink", // the ring inside the filled button
            ),
            Contrast.UI_MIN,
        )
    }

    private fun assertPairs(pairs: List<Pair<String, String>>, min: Double) {
        val failures = buildList {
            for ((mode, palette) in listOf("light" to light, "dark" to dark)) {
                for ((fg, bg) in pairs) {
                    val ratio = Contrast.ratio(palette.getValue(fg), palette.getValue(bg))
                    if (ratio < min) add("$mode $fg on $bg = ${"%.2f".format(ratio)}")
                }
            }
        }
        assertEquals("pairs under $min:1", emptyList<String>(), failures)
    }

    private companion object {
        /** WCAG AAA for body text. */
        const val AAA_TEXT = 7.0
    }
}
