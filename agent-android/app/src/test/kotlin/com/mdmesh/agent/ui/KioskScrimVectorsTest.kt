package com.mdmesh.agent.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The shared legibility vectors (`proto/vectors/kiosk-scrim.json`, version 3, also run by the console): parsing,
 * palettes, and layouts (dim, plate steps and busy exactly, ratios within 0.01).
 */
class KioskScrimVectorsTest {

    private val vectors: JsonObject by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "proto/vectors/kiosk-scrim.json").isFile }
        Json.parseToJsonElement(File(root, "proto/vectors/kiosk-scrim.json").readText()).jsonObject
    }

    private fun colour(e: JsonElement): Int = (0xFF000000L or e.jsonPrimitive.content.drop(1).toLong(16)).toInt()

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private fun parsed(e: JsonElement?): Int? =
        if (e == null || e is JsonNull) null else KioskPalette.parse(e.jsonPrimitive.content)

    private fun palette(v: JsonObject) = KioskPalette.of(parsed(v["backgroundColor"]), parsed(v["textColor"]))

    private fun int(o: JsonObject, key: String) = o[key]!!.jsonPrimitive.int

    private fun double(o: JsonObject, key: String) = o[key]!!.jsonPrimitive.double

    @Test fun `version 3 with the documented constants`() {
        assertEquals(3, int(vectors, "version"))
        assertEquals(KioskScrim.STEPS, int(vectors, "steps"))
        assertEquals(LegibilityV3.DIM_MAX_STEP, int(vectors, "dimMaxStep"))
        assertEquals(Contrast.TEXT_MIN, double(vectors, "textMin"), 0.0)
        assertEquals(Contrast.UI_MIN, double(vectors, "controlMin"), 0.0)
        assertEquals(KioskScrim.BUSY_LEVELS, int(vectors, "busySpan"))
        assertEquals(BackdropCells.CELLS_ON_LONG_EDGE, int(vectors, "cellsOnLongEdge"))
    }

    @Test fun `every parsing vector`() {
        val cases = vectors["parsing"]!!.jsonArray
        check(cases.isNotEmpty())
        for (e in cases) {
            val v = e.jsonObject
            val input = v["input"]!!.jsonPrimitive.content
            val expected = v["expected"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            assertEquals("'$input'", expected, KioskPalette.parse(input)?.let(::hex))
        }
    }

    @Test fun `every palette vector`() {
        for (e in vectors["palette"]!!.jsonArray) {
            val v = e.jsonObject
            val p = palette(v)
            val expected = v["expected"]!!.jsonObject
            assertEquals("text $v", expected["text"]!!.jsonPrimitive.content, hex(p.text))
            assertEquals("secondary $v", expected["secondary"]!!.jsonPrimitive.content, hex(p.secondary))
        }
    }

    @Test fun `every layout vector`() {
        val layouts = vectors["layout"]!!.jsonArray
        check(layouts.isNotEmpty())
        for (e in layouts) layout(e.jsonObject)
    }

    private fun layout(v: JsonObject) {
        val name = v["name"]!!.jsonPrimitive.content
        val p = palette(v)
        val expected = v["expected"]!!.jsonObject
        assertEquals(name, expected["background"]!!.jsonPrimitive.content, hex(p.background))
        assertEquals(name, expected["text"]!!.jsonPrimitive.content, hex(p.text))
        assertEquals(name, expected["secondary"]!!.jsonPrimitive.content, hex(p.secondary))
        val image = v["image"]!!.jsonObject
        val cells = image["cells"]!!.jsonArray.map { it.jsonArray }
        val grid = BackdropCells(
            int(image, "width"), int(image, "height"),
            mins = cells.map { colour(it[0]) }.toIntArray(),
            maxs = cells.map { colour(it[1]) }.toIntArray(),
        )
        assertEquals(name, int(image, "cellSize"), grid.cellSize)
        assertEquals(name, int(image, "cols"), grid.cols)
        assertEquals(name, int(image, "rows"), grid.rows)
        val window = v["window"]!!.jsonObject
        val w = double(window, "w")
        val h = double(window, "h")
        val elements = v["elements"]!!.jsonArray.map { it.jsonObject }
        fun bounds(el: JsonObject): ColourBox {
            val b = el["box"]!!.jsonObject
            return grid.bounds(
                w, h, BackdropCells.Region(double(b, "x"), double(b, "y"), double(b, "w"), double(b, "h")),
            )
        }
        val controls = elements.filter { it["kind"]!!.jsonPrimitive.content == "control" }.map(::bounds)
        val rule = LegibilityV3
        val dim = rule.dimStep(p, controls)
        assertEquals("$name dim", int(expected, "dim"), dim)
        val plates = expected["plates"]!!.jsonObject
        for (el in elements) {
            val id = el["id"]!!.jsonPrimitive.content
            val want = plates[id]!!.jsonObject
            val kind = el["kind"]!!.jsonPrimitive.content
            val box = bounds(el)
            val lo = KioskScrim.blend(p.background, box.min, dim)
            val hi = KioskScrim.blend(p.background, box.max, dim)
            assertEquals("$name/$id busy", want["busy"]!!.jsonPrimitive.boolean, KioskScrim.busy(lo, hi))
            val colour = if (kind == "secondary") p.secondary else p.text
            val step = if (kind == "control") 0 else rule.plateStep(colour, p.background, box, dim)
            assertEquals("$name/$id step", int(want, "step"), step)
            val withPlate = KioskScrim.worst(
                colour, KioskScrim.blend(p.background, lo, step), KioskScrim.blend(p.background, hi, step),
            )
            assertEquals("$name/$id ratio", double(want, "ratio"), withPlate, 0.01)
            want["ratioWithoutPlate"]?.let {
                assertEquals("$name/$id without", it.jsonPrimitive.double, KioskScrim.worst(colour, lo, hi), 0.01)
            }
        }
    }
}
