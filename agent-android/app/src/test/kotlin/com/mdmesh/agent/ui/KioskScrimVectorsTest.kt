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
 * The shared legibility vectors (`proto/vectors/kiosk-scrim.json`, version 3.2, also run by the console): parsing,
 * palettes, blends, geometry (6 decimals), label rows, and layouts (dim, plate steps and busy exactly, ratios within
 * 0.01).
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

    @Test fun `version 3_2 with the documented constants`() {
        assertEquals("3.2", vectors["version"]!!.jsonPrimitive.content)
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

    @Test fun `every blend vector`() {
        val cases = vectors["blends"]!!.jsonArray
        check(cases.isNotEmpty())
        for (e in cases) {
            val v = e.jsonObject
            val got = KioskScrim.blend(colour(v["over"]!!), colour(v["px"]!!), int(v, "step"))
            assertEquals("$v", v["expected"]!!.jsonPrimitive.content, hex(got))
        }
    }

    private fun fitOf(v: JsonObject) = if (v["mode"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content == "fit") {
        BackdropFit.FIT
    } else {
        BackdropFit.FILL
    }

    private fun focalOf(v: JsonObject): Focal =
        v["focal"]?.takeUnless { it is JsonNull }?.jsonObject?.let { Focal(double(it, "x"), double(it, "y")) }
            ?: Focal.CENTRE_POINT

    @Test fun `every geometry vector`() {
        val cases = vectors["geometry"]!!.jsonArray
        check(cases.isNotEmpty())
        for (e in cases) {
            val v = e.jsonObject
            val image = v["image"]!!.jsonObject
            val window = v["window"]!!.jsonObject
            val got = BackdropGeometry.rect(
                int(image, "width"), int(image, "height"), double(window, "w"), double(window, "h"),
                Placement(fitOf(v), focalOf(v)),
            )
            val want = v["expected"]!!.jsonObject
            for ((key, value) in listOf("x" to got.x, "y" to got.y, "w" to got.w, "h" to got.h, "scale" to got.scale)) {
                assertEquals("$v $key", double(want, key), value, 1e-6)
            }
        }
    }

    @Test fun `every row plate vector`() {
        val cases = vectors["rowPlates"]!!.jsonArray
        check(cases.isNotEmpty())
        for (e in cases) {
            val v = e.jsonObject
            fun doubles(key: String) = v[key]!!.jsonArray.map { it.jsonPrimitive.double }
            val got = LabelRows.plates(
                doubles("centres"), doubles("textWidths"), double(v, "cellWidth"), double(v, "contentLeft"),
                double(v, "contentRight"),
            )
            val want = v["expected"]!!.jsonArray.map { it.jsonObject }
            assertEquals(v["name"].toString(), want.size, got.size)
            for ((i, w) in want.withIndex()) {
                assertEquals("${v["name"]} $i x", double(w, "x"), got[i].x, 1e-9)
                assertEquals("${v["name"]} $i w", double(w, "w"), got[i].w, 1e-9)
            }
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

    /** The vector's cells, or its `#RRGGBBAA` pixels composited over [background] as the device does. */
    private fun cellsOf(image: JsonObject, background: Int): BackdropCells {
        val iw = int(image, "width")
        val ih = int(image, "height")
        val cells = image["cells"]?.jsonArray?.map { it.jsonArray }
        if (cells != null) {
            val mins = cells.map { colour(it[0]) }.toIntArray()
            return BackdropCells(iw, ih, mins, cells.map { colour(it[1]) }.toIntArray())
        }
        val px = image["pixels"]!!.jsonArray.map {
            val h = it.jsonPrimitive.content
            (h.substring(7, 9).toInt(16) shl 24) or h.substring(1, 7).toInt(16)
        }
        return BackdropCells.of(iw, ih) { y, into ->
            for (x in 0 until iw) into[x] = KioskScrim.over(px[y * iw + x], background)
        }
    }

    private fun layout(v: JsonObject) {
        val name = v["name"]!!.jsonPrimitive.content
        val p = palette(v)
        val expected = v["expected"]!!.jsonObject
        assertEquals(name, expected["background"]!!.jsonPrimitive.content, hex(p.background))
        assertEquals(name, expected["text"]!!.jsonPrimitive.content, hex(p.text))
        assertEquals(name, expected["secondary"]!!.jsonPrimitive.content, hex(p.secondary))
        val image = v["image"]!!.jsonObject
        val iw = int(image, "width")
        val ih = int(image, "height")
        val grid = cellsOf(image, p.background)
        assertEquals(name, int(image, "cellSize"), grid.cellSize)
        assertEquals(name, int(image, "cols"), grid.cols)
        assertEquals(name, int(image, "rows"), grid.rows)
        val window = v["window"]!!.jsonObject
        val w = double(window, "w")
        val h = double(window, "h")
        val placed = BackdropGeometry.rect(iw, ih, w, h, Placement(fitOf(v), focalOf(v)))
        val elements = v["elements"]!!.jsonArray.map { it.jsonObject }
        fun bounds(el: JsonObject): ColourBox {
            val b = el["box"]!!.jsonObject
            val region = BackdropCells.Region(double(b, "x"), double(b, "y"), double(b, "w"), double(b, "h"))
            return grid.bounds(placed, w, h, region, p.background)
        }
        val controls = elements.filter { it["kind"]!!.jsonPrimitive.content == "control" }.map(::bounds)
        val rule = LegibilityV3
        val dim = rule.dimStep(p, controls)
        assertEquals("$name dim", int(expected, "dim"), dim)
        val plates = expected["plates"]!!.jsonObject
        fun colourOf(el: JsonObject) = if (el["kind"]!!.jsonPrimitive.content == "secondary") p.secondary else p.text
        fun own(el: JsonObject) = when (el["kind"]!!.jsonPrimitive.content) {
            "control" -> 0
            else -> rule.plateStep(colourOf(el), p.background, bounds(el), dim)
        }
        // 3.1: the labels of a grid row share one step.
        val steps = elements.associate { it["id"]!!.jsonPrimitive.content to own(it) }.toMutableMap()
        elements.filter { it["row"] != null && it["row"] !is JsonNull }
            .groupBy { it["row"]!!.jsonPrimitive.content }
            .values.forEach { row ->
                val ids = row.map { it["id"]!!.jsonPrimitive.content }
                val shared = rule.rowStep(ids.map { steps.getValue(it) })
                ids.forEach { steps[it] = shared }
            }
        for (el in elements) {
            val id = el["id"]!!.jsonPrimitive.content
            val want = plates[id]!!.jsonObject
            val box = bounds(el)
            val lo = KioskScrim.blend(p.background, box.min, dim)
            val hi = KioskScrim.blend(p.background, box.max, dim)
            assertEquals("$name/$id busy", want["busy"]!!.jsonPrimitive.boolean, KioskScrim.busy(lo, hi))
            val colour = colourOf(el)
            val step = steps.getValue(id)
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
