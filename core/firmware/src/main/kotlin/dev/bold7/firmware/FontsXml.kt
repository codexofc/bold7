package dev.bold7.firmware

import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * tools/fonts_xml.py: the module's /system/etc/fonts.xml derived from the phone's own. BBAlpha
 * becomes sans-serif, sans-serif-condensed, serif and monospace; the 9900's fallbacks follow
 * BBAlpha Sans as unnamed families; everything else is kept byte for byte (text substitution, not
 * an XML rewrite). Each replaced family stays right after its replacement, without its name, as
 * its first fallback. The output is parsed and checked: a broken fonts.xml can stop system_server.
 */
internal object FontsXml {
    private fun fonts(vararg files: String) = listOf(400 to "normal", 400 to "italic", 700 to "normal").zip(files)

    /** Weight and style as read in each file's OS/2 table. */
    val BBALPHA = linkedMapOf(
        "sans-serif" to fonts("BBAlphaSans.ttf", "BBAlphaSansItalic.ttf", "BBAlphaSansBold.ttf"),
        "sans-serif-condensed" to fonts("BBAlphaSansCondensed.ttf", "BBAlphaSansCondensedItalic.ttf", "BBAlphaSansCondensedBold.ttf"),
        "serif" to fonts("BBAlphaSerif.ttf", "BBAlphaSerifItalic.ttf", "BBAlphaSerifBold.ttf"),
        "monospace" to fonts("BBAlphaSansMono.ttf", "BBAlphaSansMonoItalic.ttf", "BBAlphaSansMonoBold.ttf"),
    )

    /** The 9900's fallbacks in the order of its chain: no lang means any text. */
    val FALLBACKS: List<Pair<String?, List<Pair<Pair<Int, String>, String>>>> = listOf(
        null to listOf((400 to "normal") to "BBGlobalSans.ttf", (700 to "normal") to "BBGlobalSans-Bold.ttf"),
        "und-Hebr,und-Arab" to listOf((400 to "normal") to "BBSemitic.ttf"),
        "und-Thai" to listOf((400 to "normal") to "BBThai.ttf"),
        "und-Deva" to listOf((400 to "normal") to "UniversOTSDevanagari.ttf"),
        "und-Beng" to listOf((400 to "normal") to "UniversOTSBengali.ttf"),
        "und-Gujr" to listOf((400 to "normal") to "UniversOTSGujarati.ttf"),
        "und-Taml" to listOf((400 to "normal") to "UniversOTSTamil.ttf"),
        "ja" to listOf((400 to "normal") to "DroidSansJapanese.ttf"),
        "zh-Hant" to listOf((400 to "normal") to "MYuenC-SemiBold-Big5HKSCS.ttf"),
        null to listOf((400 to "normal") to "BBGlobalSansFallback.ttf"), // CJK, Hangul, arrows, maths, shapes
    )

    /** Every font file the derived fonts.xml names. */
    val FILES = BBALPHA.values.flatten().map { it.second } + FALLBACKS.flatMap { (_, f) -> f.map { it.second } }

    private fun font(ind: String, f: Pair<Pair<Int, String>, String>) =
        "$ind    <font weight=\"${f.first.first}\" style=\"${f.first.second}\">${f.second}</font>\n"

    private fun fallbackBlock(ind: String) = FALLBACKS.joinToString("") { (lang, fonts) ->
        "$ind<family${if (lang != null) " lang=\"$lang\"" else ""}>\n" + fonts.joinToString("") { font(ind, it) } + "$ind</family>\n"
    }

    fun derive(stock: String): String {
        // Read as python's text mode does: universal newlines.
        var xml = stock.replace("\r\n", "\n").replace('\r', '\n')
        // SlatePro: the fonts.xml of the BB10 build, mounted the same way.
        if ("BBAlpha" in xml || "SlatePro" in xml) throw IOException("already derived: this is the module fonts.xml, not the stock one")
        for ((name, fonts) in BBALPHA) {
            val found = Regex("(?md)^([ \\t]*)<family\\s+name=\"${Regex.escape(name)}\"([^>]*)>").findAll(xml).toList()
            if (found.size != 1) throw IOException("expected one <family name=\"$name\">, found ${found.size}")
            val m = found[0]
            val ind = m.groupValues[1]
            val ours = "$ind<family name=\"$name\">\n" + fonts.joinToString("") { font(ind, it) } + "$ind</family>\n" +
                (if (name == "sans-serif") fallbackBlock(ind) else "") + "$ind<family${m.groupValues[2]}>"
            xml = xml.substring(0, m.range.first) + ours + xml.substring(m.range.last + 1)
        }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        val first = generateSequence(root.firstChild) { it.nextSibling }.filterIsInstance<Element>().firstOrNull { it.tagName == "family" }
        val font = first?.getElementsByTagName("font")?.item(0)
        if (first?.getAttribute("name") != "sans-serif" || font == null || "BBAlpha" !in font.textContent) {
            throw IOException("the first family is not BBAlpha sans-serif")
        }
        return xml
    }
}
