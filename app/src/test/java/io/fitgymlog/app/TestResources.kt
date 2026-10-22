package io.fitgymlog.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** String resources read from the source tree, since unit tests run without Android's resource loading. */
fun stringsIn(directory: String): Map<String, String> =
    File("src/main/res/$directory").listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until strings.length).map { strings.item(it) }.map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }.toMap()

fun resourceName(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name
