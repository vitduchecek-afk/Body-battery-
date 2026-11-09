package cz.vitd.bodybattery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the Android string resources of the app module:
 *  - every supported language has exactly the translatable keys of the default (English) file
 *  - format placeholders (%1$d, %2$s …) match the default
 *  - layouts contain no hard-coded texts
 *  - the Garmin trademark "Body Battery" appears nowhere in resources
 */
class StringResourcesTest {
    private val res = listOf(File("../app/src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private val languages = listOf("cs", "de", "fr", "es", "pt-rBR", "it", "ru", "zh-rCN", "ja", "hi")
    private val placeholder = Regex("""%(\d+\$)?[-+#0 ,(]*\d*(\.\d+)?[sdfx]""")

    private fun load(file: File): Map<String, Pair<String, Boolean>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to (e.textContent to (e.getAttribute("translatable") != "false"))
        }
    }

    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    @Test
    fun allLanguagesHaveAllKeysWithSamePlaceholders() {
        val base = load(File(res, "values/strings.xml")).filterValues { it.second }
        assertTrue("default strings missing", base.size > 50)
        val problems = mutableListOf<String>()
        for (lang in languages) {
            val f = File(res, "values-$lang/strings.xml")
            if (!f.exists()) { problems += "$lang: file missing"; continue }
            val tr = load(f)
            (base.keys - tr.keys).forEach { problems += "$lang: missing '$it'" }
            (tr.keys - base.keys).forEach { problems += "$lang: unknown key '$it'" }
            for ((k, v) in tr) {
                if (!v.second) problems += "$lang: '$k' must not be redefined (not translatable)"
                val b = base[k] ?: continue
                if (placeholders(b.first) != placeholders(v.first)) problems += "$lang: '$k' placeholders ${placeholders(v.first)} != ${placeholders(b.first)}"
                if (v.first.isBlank()) problems += "$lang: '$k' is empty"
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun layoutsHaveNoHardcodedText() {
        val bad = File(res, "layout").listFiles()!!.flatMap { f ->
            Regex("""android:(text|contentDescription|hint)="([^"@?][^"]*)"""").findAll(f.readText()).map { "${f.name}: ${it.value}" }.toList()
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test
    fun noGarminTrademarkInResources() {
        val hits = res.walkTopDown().filter { it.isFile && it.extension == "xml" }
            .filter { f ->
                // the (invisible) package name cz.vitd.bodybattery is allowed
                val text = f.readText().replace("cz.vitd.bodybattery", "")
                text.contains("Body Battery", ignoreCase = true) || text.contains("BodyBattery", ignoreCase = true)
            }
            .map { it.path }.toList()
        assertEquals(hits.joinToString("\n"), 0, hits.size)
    }
}
