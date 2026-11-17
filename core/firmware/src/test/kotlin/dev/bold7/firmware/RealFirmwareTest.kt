package dev.bold7.firmware

import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The extractor on the real OS Loader and simulator (assets-private/firmware/, out of git), its
 * output compared with what tools/extract-bbos7.py and tools/import-assets.sh made: each firmware
 * tree with the sums of assets-private/<bbos7-9900|bbos7-9900-sim>/, the loader's gen/ with this
 * checkout's assets-gen/. Skipped when a file is missing (a machine without the firmware).
 */
class RealFirmwareTest {
    companion object {
        private val root = File(System.getProperty("bold7.root") ?: "../..")
        private val loader = File(root, "assets-private/firmware/bbos7-9900/9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe")
        private val simulator = File(root, "assets-private/firmware/bbos7-simulateur/BlackBerry Simulator_7.1.0.318_9900.exe")
        private val out = File(root, "core/firmware/build/real-firmware")
        private val simOut = File(root, "core/firmware/build/real-simulator")
        private val soundTable = File(root, "system/module/sounds.tsv").readText()
        private var ran = false
        private var simRan = false

        private fun extract(input: File, to: File): Boolean {
            if (!input.isFile || !File(root, "assets-gen").isDirectory) return false
            val table = File(root, "core/icons/src/main/res/raw/bbos7_icons.tsv").readText()
            val rt = Runtime.getRuntime()
            val start = System.nanoTime()
            var peak = 0L
            Extractor(table, soundTable, File(root, "apps/saisie/src/main/assets/dictionary")).extract(input, to) { _, _ -> peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()) }
            println("${input.name}: %.1f s, heap peak seen %d MB".format((System.nanoTime() - start) / 1e9, peak shr 20))
            return true
        }

        @BeforeClass
        @JvmStatic
        fun extractOnce() {
            ran = extract(loader, out)
            simRan = extract(simulator, simOut)
        }

        private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

        private fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toSortedSet()
    }

    private fun assertTreeIsTheReference(tree: File, name: String) {
        val sums = File(root, "assets-private/$name/$name.sha256").readLines()
            .associate { it.substring(66) to it.substring(0, 64) }
        // ling/ and help/: Typing's lists and the help, which tools/extract-bbos7.py does not write.
        assertEquals(sums.keys.toSortedSet(), files(tree).filterNot { it.startsWith("ling/") || it.startsWith("help/") }.toSortedSet())
        val wrong = sums.filter { (path, sum) -> sha(File(tree, path)) != sum }.keys
        assertTrue(wrong.isEmpty(), "different bytes: ${wrong.take(10)}")
    }

    @Test
    fun firmwareTreeIsTheReference() {
        assumeTrue("no OS Loader in assets-private/firmware", ran)
        assertTreeIsTheReference(File(out, "firmware"), "bbos7-9900")
    }

    /** The simulator's .cod, read from its NSIS installer, give tools/extract-bbos7.py's tree (7zz). */
    @Test
    fun simulatorTreeIsTheReference() {
        assumeTrue("no simulator in assets-private/firmware", simRan)
        assertTreeIsTheReference(File(simOut, "firmware"), "bbos7-9900-sim")
    }

    /** The files of assets-gen/ the ported blocks write, by path prefix. */
    private val blocks = listOf("launcher/res/drawable-nodpi/", "android/", "permissioncontroller/", "sysui/res/drawable-nodpi/banner_", "sysui/res/drawable-nodpi/lock_", "sysui/res/drawable-nodpi/vol_", "sysui/res/drawable-nodpi/shutdown_") +
        listOf("ui", "essai", "messages", "phone", "clock", "calendar", "contacts", "calculator", "memopad", "tasks", "pictures").map { "$it/res/drawable-nodpi/" } +
        listOf("module/system/fonts/BBAlpha", "module/system/fonts-os7/", "sounds/", "wallpapers/", "help/")

    /** Cut from captures of the simulator, not from the firmware: not ported yet. */
    private val fromCaptures = setOf("clock/res/drawable-nodpi/clk_alarm_add.png", "calendar/res/drawable-nodpi/cal_allday.png")

    @Test
    fun genIsAssetsGen() {
        assumeTrue("no OS Loader in assets-private/firmware", ran)
        val gen = File(out, "gen")
        val ref = File(root, "assets-gen")
        val mine = files(gen).filter { p -> blocks.any { p.startsWith(it) } }.toSortedSet()
        val theirs = files(ref).filter { p -> blocks.any { p.startsWith(it) } && p !in fromCaptures }.toSortedSet()
        assertEquals(theirs, mine)
        val sameBytes = mine.count { File(gen, it).readBytes().contentEquals(File(ref, it).readBytes()) }
        val differ = mine.filter { !File(gen, it).readBytes().contentEquals(File(ref, it).readBytes()) }
        println("gen: ${mine.size} files, $sameBytes identical bytes, ${differ.size} compared by pixels or metrics: $differ")
        for (p in differ) {
            when {
                p.endsWith(".ttf") -> assertTrue(TrueType.fixLineMetrics(File(ref, p).readBytes()).contentEquals(File(gen, p).readBytes()), p)
                else -> assertPixels(File(ref, p), File(gen, p))
            }
        }
    }

    /**
     * The 1,173 pages of the help (Assets.help, docs/YK.md): each a whole HTML document whose
     * title is the one searchContent.txt gives its page, and all of them the bytes RIM's own decoder
     * gave, run on the same pages (digest of every page, name then bytes, in name order).
     */
    @Test
    fun helpIsDecoded() {
        assumeTrue("no OS Loader in assets-private/firmware", ran)
        val help = File(out, "gen/help")
        val pages = help.listFiles { f -> f.name.endsWith(".html") }!!.sortedBy { it.name }
        assertEquals(1173, pages.size)
        val titles = File(help, "searchContent.txt").readLines().filter { '|' in it }
            .associate { it.substringBefore('|') to it.split('|')[1] }
        val md = MessageDigest.getInstance("SHA-256")
        for (p in pages) {
            val html = p.readText()
            assertTrue(html.startsWith("<!DOCTYPE html") && html.trimEnd().endsWith("</html>"), p.name)
            assertBalanced(html, p.name)
            if (p.name != "index.html") assertEquals(titles[p.name], unescape(Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1].trim()), p.name)
            md.update((p.name + "\n").toByteArray())
            md.update(p.readBytes())
        }
        assertEquals("ccd7b11ddaeb8962750cdec92c91ae5e351dbd3ebf4cd71779897a598a12c4c9", md.digest().joinToString("") { "%02x".format(it) })
        assertTrue(File(help, "style.css").isFile && File(help, "theme/cat_help_getting_started.png").isFile)
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&#34;", "\"").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    /** Every element closed in order, HTML's empty ones (meta, link, br, img…) aside. */
    private fun assertBalanced(html: String, name: String) {
        val empty = setOf("meta", "link", "br", "img", "hr", "input", "col", "area", "base", "param", "wbr")
        val open = ArrayDeque<String>()
        for (m in Regex("<(/?)([A-Za-z][A-Za-z0-9]*)[^>]*?(/?)>").findAll(html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), ""))) {
            val tag = m.groupValues[2].lowercase()
            when {
                m.groupValues[1] == "/" -> assertEquals(tag, open.removeLastOrNull(), "$name: </$tag>")
                m.groupValues[3] != "/" && tag !in empty -> open.addLast(tag)
            }
        }
        assertTrue(open.isEmpty(), "$name: unclosed $open")
    }

    /** OS 7's part of Typing, the same bytes as tools/import-assets.sh saisie writes (Assets.saisie). */
    @Test
    fun saisieIsImportAssets() {
        assumeTrue("no OS Loader in assets-private/firmware", ran)
        for (p in listOf("saisie/assets/os7/substitution/en.tsv", "saisie/assets/os7/dictionary/fr.tsv")) {
            val ref = File(root, "assets-gen/$p")
            assumeTrue("no $ref: tools/import-assets.sh saisie", ref.isFile)
            assertTrue(File(out, "gen/$p").readBytes().contentEquals(ref.readBytes()), p)
        }
    }

    /**
     * Every image the development build reads by name (assets-gen/<bloc>/res/, to which each module's
     * tests hold the names its code asks for) is one the extraction makes, or one in git
     * (src/main/res/drawable* of a module), which the public APK carries: the public install draws no
     * image empty. Drawn for Bold7 (D) means in git.
     */
    @Test
    fun everyImageIsExtractedOrInGit() {
        assumeTrue("no OS Loader in assets-private/firmware", ran)
        val gen = File(out, "gen")
        val inGit = listOf("apps", "core", "system", "tools").flatMap { d ->
            File(root, d).walkTopDown().onEnter { it.name != "build" }
                .filter { it.isFile && it.parentFile.name.startsWith("drawable") && it.parentFile.parentFile.path.endsWith("src/main/res") }
                .map { it.name }.toList()
        }.toSet()
        val read = File(root, "assets-gen").listFiles { f -> File(f, "res").isDirectory }.orEmpty()
            .flatMap { b -> files(File(b, "res")).map { "${b.name}/res/$it" } }
        assertTrue(read.isNotEmpty())
        assertEquals(emptyList(), read.filterNot { File(gen, it).isFile || File(it).name in inGit })
    }

    /** The sounds the module lays, each its firmware source unchanged (Assets.sons). */
    @Test
    fun moduleSoundsAreTheSources() {
        assumeTrue("no OS Loader in assets-private/firmware", ran)
        val audio = File(out, "gen/module/product/media/audio")
        val lines = soundTable.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(lines.size, files(audio).size)
        for ((dest, source) in lines) {
            val name = if (dest.startsWith("ui/")) dest else dest.substringBeforeLast('.') + "." + source.substringAfterLast('.')
            assertTrue(File(audio, name).readBytes().contentEquals(File(root, "assets-gen/sounds/$source").readBytes()), name)
        }
    }

    /**
     * The simulator is a second source: every block finds what it reads there, but the YouTube icon
     * and the help, whose edition there grows its grammar page by page (docs/YK.md, section 7).
     */
    @Test
    fun simulatorGivesEveryBlock() {
        assumeTrue("no simulator in assets-private/firmware", simRan)
        val a = files(File(out, "gen"))
        val b = files(File(simOut, "gen"))
        assertEquals(a.filterNot { it.startsWith("help/") }.toSet() - "launcher/res/drawable-nodpi/bbos7_youtube.png", b.toSet())
        val differ = b.filter { !File(out, "gen/$it").readBytes().contentEquals(File(simOut, "gen/$it").readBytes()) }
        println("simulator gen: ${b.size} files, ${differ.size} different from the loader's: $differ")
    }

    /** The module's build applies tools/ttf_metrics.py: when it has run, its fonts are ours. */
    @Test
    fun fontsAreTheModulesBuild() {
        assumeTrue("no OS Loader in assets-private/firmware", ran)
        val built = File(root, "system/module/build/fonts/system/fonts")
        assumeTrue("module fonts not built", built.isDirectory)
        for (f in built.listFiles()!!.filter { it.name.startsWith("BBAlpha") })
            assertTrue(f.readBytes().contentEquals(File(out, "gen/module/system/fonts/${f.name}").readBytes()), f.name)
    }

    /**
     * Pixels compared as decoded by ImageIO, except a grey PNG of ImageMagick (msg_folder.png),
     * which ImageIO turns linear: that one by our decoder, checked against ImageIO in PngTest.
     */
    private fun assertPixels(ref: File, mine: File) {
        val a = Png.decode(ref.readBytes())
        val b = ImageIO.read(mine)
        assertEquals(a.width to a.height, b.width to b.height, ref.name)
        if (ImageIO.read(ref).colorModel.colorSpace.type != java.awt.color.ColorSpace.TYPE_GRAY)
            assertTrue(a.argb.contentEquals(ImageIO.read(ref).getRGB(0, 0, a.width, a.height, null, 0, a.width)), "decoders differ on ${ref.name}")
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a[x, y]
            val q = b.getRGB(x, y)
            if (p ushr 24 == 0 && q ushr 24 == 0) continue // fully transparent: the colour does not show
            if (p != q) fail("${ref.name} ($x, $y): %08x, ours %08x".format(p, q))
        }
    }
}
