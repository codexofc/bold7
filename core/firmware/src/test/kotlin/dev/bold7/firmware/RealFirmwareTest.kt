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
            Extractor(table, soundTable).extract(input, to) { _, _ -> peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()) }
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
        assertEquals(sums.keys.toSortedSet(), files(tree))
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
        listOf("module/system/fonts/BBAlpha", "module/system/fonts-os7/", "sounds/", "wallpapers/")

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

    /** The simulator is a second source: every block finds what it reads there, but the YouTube icon. */
    @Test
    fun simulatorGivesEveryBlock() {
        assumeTrue("no simulator in assets-private/firmware", simRan)
        val a = files(File(out, "gen"))
        val b = files(File(simOut, "gen"))
        assertEquals(a - "launcher/res/drawable-nodpi/bbos7_youtube.png", b)
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
