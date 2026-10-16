package com.mdmesh.policy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for GitHub #76: the toggle-policy map must be resolved per operation (`registry::togglePolicies`), never
 * injected or stored. A stored map built before the agent is Device Owner misses every Device-Owner policy for the
 * life of the process. This scans the production sources of every module for a stored or injected
 * `Map<String, TogglePolicy>`, and the app module (the Hilt wiring) for a `togglePolicies()` call whose result
 * could be captured: it passes `registry::togglePolicies`.
 */
class TogglePolicyWiringTest {

    private val mapType = """Map<\s*String\s*,\s*(@JvmSuppressWildcards\s+)?TogglePolicy\s*>"""

    /** A property (constructor `val`/`var` included) of the map type. */
    private val stored = Regex("""\b(val|var)\s+\w+\s*:\s*$mapType""")

    /** In the app module, where the Hilt bindings and injection sites live: any mention, or a call. */
    private val anywhere = Regex(mapType)
    private val called = Regex("""\btogglePolicies\s*\(""")

    private fun projectRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun mainSources(root: File): List<File> = root.listFiles().orEmpty()
        .map { File(it, "src/main") }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }

    private fun isOffending(line: String, inApp: Boolean): Boolean =
        stored.containsMatchIn(line) || (inApp && (anywhere.containsMatchIn(line) || called.containsMatchIn(line)))

    @Test
    fun `no production code stores or injects the toggle policy map`() {
        val root = projectRoot()
        val sources = mainSources(root)
        assertTrue("found no sources under $root", sources.any { it.name == "CapabilityRegistry.kt" })

        val offenders = sources.flatMap { file ->
            val inApp = file.relativeTo(root).path.startsWith("app")
            file.readLines().mapIndexedNotNull { i, line ->
                if (isOffending(line, inApp)) "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
            }
        }

        assertTrue(
            "Inject or store `() -> Map<String, TogglePolicy>` (registry::togglePolicies) instead:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
