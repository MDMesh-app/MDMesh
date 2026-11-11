package com.mdmesh.agent.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The shared scrim vectors (`proto/vectors/kiosk-scrim.json`, also run by the console): the palette, the step and
 * alpha exactly, the covered ratio within 0.01.
 */
class KioskScrimVectorsTest {

    private val vectors: JsonObject by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "proto/vectors/kiosk-scrim.json").isFile }
        Json.parseToJsonElement(File(root, "proto/vectors/kiosk-scrim.json").readText()).jsonObject
    }

    private fun colour(e: JsonElement?): Int? =
        if (e == null || e is JsonNull) null else (0xFF000000L or e.jsonPrimitive.content.drop(1).toLong(16)).toInt()

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /** The palette from the configuration's strings, parsed the way the launcher parses them. */
    private fun palette(v: JsonObject) = KioskPalette.of(parsed(v["backgroundColor"]), parsed(v["textColor"]))

    private fun parsed(e: JsonElement?): Int? =
        if (e == null || e is JsonNull) null else KioskPalette.parse(e.jsonPrimitive.content)

    @Test fun `version 1 with the documented constants`() {
        assertEquals(2, vectors["version"]!!.jsonPrimitive.int)
        assertEquals(KioskScrim.STEPS, vectors["steps"]!!.jsonPrimitive.int)
        assertEquals(2, KioskScrim.allowed(100))
        assertEquals(Contrast.TEXT_MIN, vectors["textMin"]!!.jsonPrimitive.double, 0.0)
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

    @Test fun `every scrim vector`() {
        val scrims = vectors["scrim"]!!.jsonArray
        check(scrims.isNotEmpty())
        for (e in scrims) {
            val v = e.jsonObject
            val name = v["name"]!!.jsonPrimitive.content
            val p = palette(v)
            val samples = v["samples"]!!.jsonArray.map { colour(it)!! }.toIntArray()
            val expected = v["expected"]!!.jsonObject
            assertEquals(name, expected["text"]!!.jsonPrimitive.content, hex(p.text))
            assertEquals(name, expected["secondary"]!!.jsonPrimitive.content, hex(p.secondary))
            assertEquals(name, expected["scrim"]!!.jsonPrimitive.content, hex(p.background))
            val step = KioskScrim.step(p, samples)
            assertEquals(name, expected["step"]!!.jsonPrimitive.int, step)
            assertEquals(name, expected["alpha"]!!.jsonPrimitive.double, KioskScrim.alpha(step).toDouble(), 1e-6)
            val without = expected["ratioWithoutScrim"]
            val ratio = expected["ratio"]
            if (ratio == null || ratio is JsonNull) {
                assertNull(name, KioskScrim.covered(p.text, p.background, samples, step))
            } else {
                // The lower of the two text colours: what the scrim had to bring to 4.5:1.
                fun covered(at: Int) =
                    listOf(p.text, p.secondary).minOf { KioskScrim.covered(it, p.background, samples, at)!! }
                assertEquals(name, ratio.jsonPrimitive.double, covered(step), 0.01)
                assertEquals(name, without!!.jsonPrimitive.double, covered(0), 0.01)
            }
        }
    }
}
