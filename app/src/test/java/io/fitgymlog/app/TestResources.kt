package io.fitgymlog.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

/** String resources read from the source tree, since unit tests run without Android's resource loading. */
fun stringsIn(directory: String): Map<String, String> =
    elementsIn(directory, "string").associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }

/** Names of the strings and plurals that need a translation, as "string/name" and "plurals/name". */
fun translatableIn(directory: String): Set<String> =
    listOf("string", "plurals").flatMap { tag ->
        elementsIn(directory, tag)
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .map { "$tag/${it.attributes.getNamedItem("name").nodeValue}" }
    }.toSet()

private fun elementsIn(directory: String, tag: String): List<Node> =
    requireNotNull(File("src/main/res/$directory").listFiles { file -> file.extension == "xml" }) { "No $directory in ${File("").absolutePath}" }.flatMap { file ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName(tag)
        (0 until nodes.length).map { nodes.item(it) }
    }

fun resourceName(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name
