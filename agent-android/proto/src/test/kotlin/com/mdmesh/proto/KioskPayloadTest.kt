package com.mdmesh.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class KioskPayloadTest {

    @Test
    fun `full payload round-trips`() {
        val p = KioskApplyPayload(
            mode = "launcher",
            allowedPackages = listOf("com.a", "com.b"),
            pinPackage = null,
            features = KioskFeaturesDto(home = true, lockButtons = false),
            exitMode = "gesture",
            password = "1234",
            theme = KioskThemeDto(
                "#000000", "#ffffff", "SMALL",
                KioskBackgroundImageDto(
                    url = "https://mdm.example/files/kiosk-background.png",
                    sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ),
            ),
        )
        val json = ProtocolJson.json.encodeToString(KioskApplyPayload.serializer(), p)
        assertEquals(p, ProtocolJson.json.decodeFromString(KioskApplyPayload.serializer(), json))
    }

    @Test
    fun `minimal payload uses defaults`() {
        val p = ProtocolJson.json.decodeFromString(KioskApplyPayload.serializer(), "{}")
        assertEquals("launcher", p.mode)
        assertEquals("gesture", p.exitMode)
        assertEquals(emptyList<String>(), p.allowedPackages)
    }

    @Test fun `background images are the same image by sha256 alone`() {
        val a = KioskBackgroundImageDto("https://old.example/f/1.png", "ABCDEF" + "0".repeat(58))
        val b = KioskBackgroundImageDto("https://new.example/f/1.png", "abcdef" + "0".repeat(58))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        org.junit.Assert.assertNotEquals(a, b.copy(sha256 = "1".repeat(64)))
        val k = KioskApplyPayload(theme = KioskThemeDto(backgroundImage = a))
        assertEquals(k, k.copy(theme = KioskThemeDto(backgroundImage = b)))
    }

    @Test fun `protocol 1_10 fit and focal point are read, and absent means fill around the centre`() {
        val sha = "a".repeat(64)
        val old = ProtocolJson.json.decodeFromString(
            KioskBackgroundImageDto.serializer(), """{"url":"https://x/1.png","sha256":"$sha"}""",
        )
        assertEquals(null, old.fit)
        assertEquals(false, old.letterboxed)
        assertEquals(0.5, old.focalXOrCentre, 0.0)
        val fit = ProtocolJson.json.decodeFromString(
            KioskBackgroundImageDto.serializer(), """{"url":"https://x/1.png","sha256":"$sha","fit":"fit"}""",
        )
        assertEquals(true, fit.letterboxed)
        val focal = ProtocolJson.json.decodeFromString(
            KioskBackgroundImageDto.serializer(),
            """{"url":"https://x/1.png","sha256":"$sha","focalX":0.25,"focalY":1.5}""",
        )
        assertEquals(0.25, focal.focalXOrCentre, 0.0)
        assertEquals("out of range: clamped", 1.0, focal.focalYOrCentre, 0.0)
        // A new fit or focal point is a change (the backdrop is drawn again); a new URL alone is not.
        org.junit.Assert.assertNotEquals(old, fit)
        org.junit.Assert.assertNotEquals(old, focal)
        assertEquals(old, old.copy(url = "https://y/1.png"))
    }

    @Test fun `a kiosk's identity leaves out its appearance`() {
        val k = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
        val looks = k.copy(theme = KioskThemeDto(backgroundColor = "#FFFFFF", iconSize = "LARGE"))
        assertEquals(true, k.sameKioskAs(looks))
        assertEquals(false, k.sameKioskAs(k.copy(allowedPackages = listOf("com.a", "com.b"))))
        assertEquals(false, k.sameKioskAs(k.copy(exitMode = "remote")))
        assertEquals(false, k.sameKioskAs(null))
    }

    private fun decode(o: JsonObject) = ProtocolJson.json.decodeFromJsonElement(KioskApplyPayload.serializer(), o)

    /** [v] changed: a flipped flag, a longer string or list, or a value where there was none. */
    private fun changed(v: JsonElement?): JsonElement = when {
        v == null || v is JsonNull -> JsonPrimitive("x")
        v is JsonArray -> JsonArray(v + JsonPrimitive("com.other"))
        v is JsonPrimitive && v.booleanOrNull != null -> JsonPrimitive(!v.boolean)
        v is JsonPrimitive -> JsonPrimitive(v.content + "x")
        else -> error("unexpected $v")
    }

    /**
     * Every field but `theme`, and every lock-task feature, is the kiosk's identity: changing any one of them is a
     * re-entry. Walks the serialized payload, so a field added later is covered without touching this test.
     */
    @Test fun `every non-theme field and every lock-task feature is identity`() {
        val base = KioskApplyPayload(
            mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"), password = "1234",
        )
        val json = ProtocolJson.json.encodeToJsonElement(KioskApplyPayload.serializer(), base).jsonObject
        val descriptor = KioskApplyPayload.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)
        check("features" in names && "theme" in names)
        val features = json["features"]?.jsonObject ?: JsonObject(emptyMap())
        val featureNames = descriptor.getElementDescriptor(names.indexOf("features")).let { f ->
            (0 until f.elementsCount).map(f::getElementName)
        }
        val mutants = names.filter { it != "theme" && it != "features" }
            .map { it to JsonObject(json + (it to changed(json[it]))) } +
            featureNames.map { f ->
                val flipped = JsonPrimitive(!((features[f] as? JsonPrimitive)?.booleanOrNull ?: false))
                "features.$f" to JsonObject(json + ("features" to JsonObject(features + (f to flipped))))
            }
        check(mutants.size >= 5 + 7) { "fields and features covered: ${mutants.size}" }
        for ((name, mutant) in mutants) {
            org.junit.Assert.assertFalse("$name is identity", base.sameKioskAs(decode(mutant)))
        }
        val restyled = JsonObject(json + ("theme" to JsonObject(mapOf("backgroundColor" to JsonPrimitive("#FFFFFF")))))
        assertEquals("theme is appearance", true, base.sameKioskAs(decode(restyled)))
    }
}
