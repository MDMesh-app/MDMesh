package com.mdmesh.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for GitHub #76: the toggle policies must be resolved per operation (`registry::togglePolicies`, read once
 * per operation), never injected or stored. A result read before the agent is Device Owner misses every Device-Owner
 * policy for the life of the process. This scans the production sources of every module for:
 *  - a property or local typed `Map<String, TogglePolicy>` or `ResolvedToggles` (types may span lines);
 *  - a property or local holding a `togglePolicies()` call (inferred type);
 * and, in the app module (the Hilt wiring), for any `togglePolicies()` call, a parameter typed with either, or any
 * mention of the map type. A provider (`() -> ResolvedToggles = registry::togglePolicies`) is the correct wiring.
 */
class TogglePolicyWiringTest {

    private val mapType = """Map\s*<\s*String\s*,\s*(@JvmSuppressWildcards\s+)?TogglePolicy\s*>"""
    private val type = """($mapType|ResolvedToggles\b(?!\s*\())"""

    /** In every module. `\s` spans line breaks, so multi-line declarations are caught too. */
    private val everywhere = listOf(
        Regex("""\b(val|var)\s+\w+\s*:\s*$type"""),
        Regex("""\b(val|var)\s+\w+\s*=\s*[\w.\s]*\btogglePolicies\s*\("""),
    )

    /** In the app module only: a call, an injected parameter, or a map-typed binding. */
    private val inAppOnly = listOf(
        Regex("""\btogglePolicies\s*\("""),
        Regex("""\b\w+\s*:\s*$type"""),
        Regex(mapType),
    )

    /** The 1-based lines of [text] where a forbidden pattern starts. */
    private fun offendingLines(text: String, inApp: Boolean): List<Int> {
        val rules = if (inApp) everywhere + inAppOnly else everywhere
        return rules.flatMap { rule -> rule.findAll(text).map { lineOf(text, it.range.first) } }.distinct().sorted()
    }

    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1

    private fun projectRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun mainSources(root: File): List<File> = root.listFiles().orEmpty()
        .map { File(it, "src/main") }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }

    @Test
    fun `no production code stores or injects the toggle policies`() {
        val root = projectRoot()
        val sources = mainSources(root)
        assertTrue("found no sources under $root", sources.any { it.name == "CapabilityRegistry.kt" })

        // The resolver defines the result type, so it is the one file that holds the map.
        val offenders = sources.filter { it.name != "TogglePolicyResolver.kt" }.flatMap { file ->
            val path = file.relativeTo(root).path
            val lines = file.readLines()
            offendingLines(file.readText(), inApp = path.startsWith("app")).map { "$path:$it: ${lines[it - 1].trim()}" }
        }

        assertTrue(
            "Inject `() -> ResolvedToggles` (registry::togglePolicies) and read it once per operation instead:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the guard catches the shapes that freeze the policies`() {
        val frozen = listOf(
            "class A(private val toggles: Map<String, TogglePolicy>)",
            "class A(private val toggles: Map<String, @JvmSuppressWildcards TogglePolicy>)",
            "class A(\n  private val toggles:\n    Map<\n      String,\n      TogglePolicy\n    >,\n)",
            "class A { private val toggles: ResolvedToggles = r.togglePolicies() }",
            "class A(registry: CapabilityRegistry) { private val toggles = registry.togglePolicies() }",
            "private val toggles =\n    registry\n        .togglePolicies()",
        )
        frozen.forEach { assertTrue(it, offendingLines(it, inApp = false).isNotEmpty()) }

        val appWiring = listOf(
            "fun provide(registry: CapabilityRegistry): ResolvedToggles = registry.togglePolicies()",
            "fun provideApplier(toggles: ResolvedToggles): ConfigApplier = ConfigApplier({ toggles })",
            "fun provideToggles(r: CapabilityRegistry): Map<String, TogglePolicy> = r.togglePolicies().policies",
        )
        appWiring.forEach { assertTrue(it, offendingLines(it, inApp = true).isNotEmpty()) }
    }

    @Test
    fun `the guard accepts a provider and per-operation reads`() {
        val fine = listOf(
            "fun provide(r: CapabilityRegistry): () -> ResolvedToggles = r::togglePolicies",
            "ConfigApplier(registry::togglePolicies, kiosk, location::set, store)",
            "class A(private val toggles: () -> ResolvedToggles) { fun f() { val policies = toggles() } }",
        )
        fine.forEach { assertEquals(it, emptyList<Int>(), offendingLines(it, inApp = true)) }
        // Outside app, a private function may take the result of one read as a parameter.
        val read = "private fun applyLocked(doc: Doc, toggles: ResolvedToggles) = Unit"
        assertEquals(emptyList<Int>(), offendingLines(read, inApp = false))
    }
}
