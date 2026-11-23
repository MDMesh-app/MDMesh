package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * app/src/test/robolectric-sdks.txt lists the API levels the Robolectric tests use; CI caches Robolectric's
 * per-level jars under a key from it. A level added to a test but not to the file would download on every run.
 */
class RobolectricSdksTest {

    @Test fun `the CI cache's SDK list matches the tests`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val tests = File(root, "app/src/test")
        val config = Regex("""\bsdk\s*=\s*\[([0-9,\s]+)]""")
        val used = tests.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> config.findAll(f.readText()).flatMap { it.groupValues[1].split(',') } }
            .map { it.trim().toInt() }
            .toSortedSet()
        val listed = File(tests, "robolectric-sdks.txt").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.toInt() }.toSortedSet()
        assertEquals(used, listed)
    }
}
