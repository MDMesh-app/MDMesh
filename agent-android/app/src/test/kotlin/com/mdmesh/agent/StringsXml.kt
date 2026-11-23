package com.mdmesh.agent

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The English text of app/src/main/res/values/strings.xml, for tests that check copy without a device. */
object StringsXml {

    private val byName: Map<String, String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "app/src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'")
        }
    }

    /** The text of R.string.[name]. */
    operator fun get(name: String): String = byName[name] ?: error("no string $name")
}
