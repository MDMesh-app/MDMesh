package com.mdmesh.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #220: every DataStore file is opened by [AgentDataStores], once, with its corruption handler, mirror and count. A
 * delegate or factory call anywhere else opens one without them (and a second instance on a file fails every read).
 * A source scan, so a fully qualified call or a wildcard import is caught as well as an import.
 */
class StoreFactoryOnlyTest {

    private val forbidden = Regex(
        """\b(Preference|MultiProcess)?DataStoreFactory\b|\b(preferencesDataStore|dataStore)\s*\(""",
    )

    /** Where opening a DataStore is the point: the factory, and tests writing files as an older agent did. */
    private val allowed = setOf(
        "core/src/main/kotlin/com/mdmesh/core/store/AgentDataStores.kt",
        "core/src/test/kotlin/com/mdmesh/core/store/AgentDataStoresTest.kt",
        "core/src/test/kotlin/com/mdmesh/core/store/AgentDataStoresUpgradeTest.kt",
        "core/src/test/kotlin/com/mdmesh/core/store/StoreFactoryOnlyTest.kt",
    )

    @Test
    fun `no DataStore is opened outside AgentDataStores`() {
        val root = File("..").canonicalFile // unit tests run in the module's directory
        val sources = root.listFiles().orEmpty().map { File(it, "src") }.filter { it.isDirectory }
            .flatMap { src -> src.walk().filter { it.extension == "kt" || it.extension == "java" }.toList() }
        assertTrue("found the agent's sources under $root", sources.size > 100)
        val offenders = sources.map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it !in allowed && forbidden.containsMatchIn(File(root, it).readText()) }
        assertEquals("open DataStore files through AgentDataStores", emptyList<String>(), offenders)
    }
}
