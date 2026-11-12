package com.mdmesh.agent.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The shared `labelLines` and `direction` vectors with the platform's own Unicode data: Android 7 (ICU with Unicode
 * 8), 8, 11 and 15.
 * The spec's conditions on top of UAX #29 make the cuts the same on all of them.
 */
@RunWith(RobolectricTestRunner::class)
abstract class LabelLineVectorsTest {

    @Test fun everyLabelLineVector() = checkLabelLineVectors()

    @Test fun everyDirectionVector() = checkDirectionVectors()
}

private fun vectors(): JsonObject {
    val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "proto/vectors/kiosk-scrim.json").isFile }
    return Json.parseToJsonElement(File(root, "proto/vectors/kiosk-scrim.json").readText()).jsonObject
}

/** Every `direction` vector against [LabelDirection] with the platform's own Unicode data. */
fun checkDirectionVectors() {
    val cases = vectors()["direction"]!!.jsonArray
    check(cases.size >= 15)
    for (e in cases) {
        val v = e.jsonObject
        val text = v["text"]!!.jsonPrimitive.content
        val want = v["expected"]!!.jsonPrimitive.content == "rtl"
        assertEquals(v["name"].toString(), want, LabelDirection.isRtl(text))
    }
}

/** Every `labelLines` vector against [LabelLines] with the platform's ICU ([IcuGraphemes]). */
fun checkLabelLineVectors() {
    val vectors = vectors()
    run {
        val cases = vectors["labelLines"]!!.jsonArray
        check(cases.size >= 29)
        for (e in cases) {
            val v = e.jsonObject
            val text = v["text"]!!.jsonPrimitive.content
            val widths = v["widths"]!!.jsonArray.map { it.jsonPrimitive.double }
            check(widths.size == text.length) { "${v["name"]}: one width per UTF-16 unit" }
            val widthOf = text.indices.associate { text[it] to widths[it] } +
                (LabelLines.ELLIPSIS[0] to v["ellipsisWidth"]!!.jsonPrimitive.double)
            val box = v["box"]!!.jsonPrimitive.double
            val max = v["maxLines"]!!.jsonPrimitive.int
            val got = LabelLines.lines(text, box, max, IcuGraphemes) { s -> s.sumOf { widthOf.getValue(it) } }
            assertEquals(v["name"].toString(), v["expected"]!!.jsonArray.map { it.jsonPrimitive.content }, got)
        }
    }
}

/** Android 7: Unicode 8 grapheme data. */
@Config(sdk = [24])
class LabelLineVectors24Test : LabelLineVectorsTest()

/** Android 8: Unicode 9. */
@Config(sdk = [26])
class LabelLineVectors26Test : LabelLineVectorsTest()

/** Android 11: Unicode 13. */
@Config(sdk = [30])
class LabelLineVectors30Test : LabelLineVectorsTest()

// Android 15 (Unicode 15) runs the same check in KioskLabelLayoutPortraitTest: an SDK 35 class of its own here fails
// to start under Robolectric 4.14 (FileSystemAlreadyExistsException copying the native runtime's fonts).
