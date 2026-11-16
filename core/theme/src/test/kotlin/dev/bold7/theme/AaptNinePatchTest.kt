package dev.bold7.theme

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every 9-patch of this checkout's assets-gen read by [NinePatchBorder] as aapt2 compiles it into
 * the APKs (its npTc chunk): the same stretch regions and padding, so an image read from the
 * mounted folder lays out as its compiled resource did. Skipped without assets-gen or aapt2.
 */
class AaptNinePatchTest {
    private val root = File(System.getProperty("bold7.root") ?: "../..")

    private fun aapt2(): File? {
        val local = Properties().apply { File(root, "local.properties").takeIf { it.isFile }?.reader()?.use { load(it) } }
        val sdk = local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: return null
        return File(sdk, "build-tools").listFiles()?.sortedBy { it.name }?.map { File(it, "aapt2") }?.lastOrNull { it.isFile }
    }

    /** The npTc chunk of a compiled .flat (the PNG is stored in it as is), in file order (big-endian). */
    private fun chunkOf(flat: ByteArray): NinePatchSpec? {
        val tag = "npTc".toByteArray()
        val at = (0..flat.size - 4).firstOrNull { i -> (0..3).all { flat[i + it] == tag[it] } } ?: return null
        val b = ByteBuffer.wrap(flat, at + 4, flat.size - at - 4).slice()
        val nx = b.get(1).toInt()
        val ny = b.get(2).toInt()
        val pad = IntArray(4) { b.getInt(12 + 4 * it) } // left, right, top, bottom
        return NinePatchSpec(
            IntArray(nx) { b.getInt(32 + 4 * it) },
            IntArray(ny) { b.getInt(32 + 4 * (nx + it)) },
            intArrayOf(pad[0], pad[2], pad[1], pad[3]),
        )
    }

    @Test
    fun sameRegionsAndPaddingAsAapt2() {
        val tool = aapt2()
        val gen = File(root, "assets-gen")
        assumeTrue("no aapt2 or no assets-gen", tool != null && gen.isDirectory)
        val patches = gen.walkTopDown().filter { it.name.endsWith(".9.png") && it.parentFile.parentFile.name == "res" }.toList()
        assertTrue(patches.isNotEmpty())
        val out = File(root, "core/theme/build/aapt-check").apply { deleteRecursively(); mkdirs() }
        for (f in patches) {
            val p = ProcessBuilder(tool!!.path, "compile", "-o", out.path, f.path).redirectErrorStream(true).start()
            val log = p.inputStream.readBytes().decodeToString()
            check(p.waitFor() == 0) { "aapt2 on $f: $log" }
            val flat = File(out, "${f.parentFile.name}_${f.name}.flat")
            val expected = assertNotNull(chunkOf(flat.readBytes()), "no npTc in $flat")
            val img = ImageIO.read(f)
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            val mine = assertNotNull(NinePatchBorder.read(px, img.width, img.height), "$f")
            assertContentEquals(expected.xDivs, mine.xDivs, "x of $f")
            assertContentEquals(expected.yDivs, mine.yDivs, "y of $f")
            assertContentEquals(expected.padding, mine.padding, "padding of $f")
        }
        println("${patches.size} 9-patches match aapt2")
    }
}
