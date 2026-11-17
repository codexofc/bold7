package dev.bold7.firmware

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The ports of the module build's Python tools (tools/ttf_metrics.py, fonts_xml.py, bootanim.py),
 * which the phone now runs at install: same bytes as the scripts, run here on this checkout's
 * assets-gen/. Skipped without macOS's python (the one whose pyexpat loads), assets-gen or magick.
 */
class PythonPortsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root = File(System.getProperty("bold7.root") ?: "../..")
    private val gen = File(root, "assets-gen")
    private val python = File("/usr/bin/python3")

    private fun run(vararg args: Any) {
        val p = ProcessBuilder(listOf(python.path, "-I") + args.map { it.toString() }).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), out)
    }

    private fun need(f: File) = assumeTrue("$f missing", python.canExecute() && f.exists())

    @Test
    fun ttfMetricsGivesTheScriptsBytes() {
        val src = File(gen, "module/system/fonts")
        need(src)
        val out = tmp.newFolder()
        run(File(root, "tools/ttf_metrics.py"), src, out)
        val fonts = src.listFiles { f -> f.name.endsWith(".ttf") }!!
        assertTrue(fonts.size == 12)
        for (f in fonts) assertTrue(TrueType.fixLineMetrics(f.readBytes()).contentEquals(File(out, f.name).readBytes()), f.name)
    }

    @Test
    fun fontsXmlGivesTheScriptsBytes() {
        val stock = File(gen, "device/fonts.xml")
        need(stock)
        val out = File(tmp.root, "fonts.xml")
        run(File(root, "tools/fonts_xml.py"), stock, File(gen, "module/system/fonts"), out, File(gen, "module/system/fonts-os7"))
        assertEquals(out.readText(), FontsXml.derive(stock.readText()))
    }

    @Test
    fun fontsXmlRefusesWhatIsNotStock() {
        val stock = File(gen, "device/fonts.xml")
        need(stock)
        val derived = FontsXml.derive(stock.readText())
        assertFailsWith<IOException> { FontsXml.derive(derived) }
        assertFailsWith<IOException> { FontsXml.derive(stock.readText().replace("<family name=\"serif\"", "<family")) }
        assertFailsWith<Exception> { FontsXml.derive(stock.readText().replace("</familyset>", "")) }
    }

    @Test
    fun bootAnimationGivesTheScriptsBytes() {
        val logo = File(gen, "bootanim/logo.png")
        need(logo)
        assumeTrue("magick missing", listOf("/opt/homebrew/bin/magick", "/usr/local/bin/magick").any { File(it).canExecute() })
        val out = File(tmp.root, "bootanimation.zip")
        val pb = ProcessBuilder(python.path, "-I", File(root, "tools/bootanim.py").path, "build", logo.path, out.path).redirectErrorStream(true)
        pb.environment()["PATH"] = "/opt/homebrew/bin:/usr/local/bin:" + pb.environment()["PATH"]
        val p = pb.start()
        val log = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), log)
        assertTrue(BootAnimation.build(Png.decode(logo.readBytes())).contentEquals(out.readBytes()))
    }

    @Test
    fun bootAnimationWithoutLogoPassesTheScriptsCheck() {
        need(File(root, "tools/bootanim.py"))
        val zip = File(tmp.root, "bootanimation.zip")
        zip.writeBytes(BootAnimation.build(null))
        run(File(root, "tools/bootanim.py"), "check", zip)
    }
}
