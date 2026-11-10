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
            "mdm_ok_ink" to "mdm_ok_soft",
            "mdm_warn_ink" to "mdm_warn_soft",
            "mdm_alert_ink" to "mdm_alert_soft",
        )
        assertPairs(pairs, Contrast.TEXT_MIN)
    }

    @Test fun `field outline and focus ring pass 3 to 1 in both modes`() {
        assertPairs(
            listOf("mdm_input_border" to "mdm_surface", "mdm_input_border" to "mdm_app", "mdm_ink" to "mdm_surface"),
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
}
