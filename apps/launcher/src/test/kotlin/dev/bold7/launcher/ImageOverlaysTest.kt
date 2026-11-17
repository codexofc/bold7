package dev.bold7.launcher

import dev.bold7.theme.NinePatchBorder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Properties
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The 9-patches the fabricated overlay puts on the framework (ImageOverlays.HOSTS), compiled as
 * aapt2 compiles the RROs' copies: the same stretch regions, padding and optical insets in the
 * same chunks, in a PNG whose chunks are well formed and whose pixels are the image's without its
 * border. Skipped without assets-gen or aapt2.
 */
class ImageOverlaysTest {
    private val root = File("../..")

    private fun aapt2(): File? {
        val local = Properties().apply { File(root, "local.properties").takeIf { it.isFile }?.reader()?.use { load(it) } }
        val sdk = local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: return null
        return File(sdk, "build-tools").listFiles()?.sortedBy { it.name }?.map { File(it, "aapt2") }?.lastOrNull { it.isFile }
    }

    /** Type to data of each chunk of a PNG starting at [from] in [bytes], CRCs checked. */
    private fun chunks(bytes: ByteArray, from: Int): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val b = ByteBuffer.wrap(bytes)
        var at = from + 8
        while (at + 8 <= bytes.size) {
            val len = b.getInt(at)
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            val body = bytes.copyOfRange(at + 4, at + 8 + len)
            assertEquals(CRC32().apply { update(body) }.value.toInt(), b.getInt(at + 8 + len), "CRC of $type")
            out.putIfAbsent(type, body.copyOfRange(4, body.size))
            if (type == "IEND") break
            at += 12 + len
        }
        return out
    }

    /** Stretch regions and padding (left, right, top, bottom) of an npTc chunk as a file holds it. */
    private fun regions(np: ByteArray): List<List<Int>> {
        val b = ByteBuffer.wrap(np)
        val nx = np[1].toInt()
        val ny = np[2].toInt()
        return listOf(List(nx) { b.getInt(32 + 4 * it) }, List(ny) { b.getInt(32 + 4 * (nx + it)) }, List(4) { b.getInt(12 + 4 * it) })
    }

    @Test
    fun ninePatchesAsAapt2CompilesThem() {
        val tool = aapt2()
        val images = ImageOverlays.HOSTS.values.toSet()
        val patches = listOf("android", "permissioncontroller")
            .flatMap { File(root, "assets-gen/$it/res/drawable-nodpi").listFiles().orEmpty().toList() }
            .filter { it.name.endsWith(".9.png") && it.name.removeSuffix(".9.png") in images }
        assumeTrue("no aapt2 or no assets-gen", tool != null && patches.isNotEmpty())
        val out = File("build/aapt-overlays").apply { deleteRecursively(); mkdirs() }
        for (f in patches) {
            val p = ProcessBuilder(tool!!.path, "compile", "-o", out.path, f.path).redirectErrorStream(true).start()
            val log = p.inputStream.readBytes().decodeToString()
            check(p.waitFor() == 0) { "aapt2 on $f: $log" }
            val flat = File(out, "drawable-nodpi_${f.name}.flat").readBytes()
            val png = (0..flat.size - 8).first { i -> flat[i] == 0x89.toByte() && String(flat, i + 1, 3) == "PNG" }
            val expected = chunks(flat, png)

            val img = ImageIO.read(f)
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            val spec = assertNotNull(NinePatchBorder.read(px, img.width, img.height), "$f")
            val inner = ByteArrayOutputStream().also { ImageIO.write(img.getSubimage(1, 1, img.width - 2, img.height - 2), "png", it) }.toByteArray()
            val mine = ImageOverlays.withChunks(
                inner,
                listOfNotNull("npTc" to ImageOverlays.ninePatchChunk(spec), ImageOverlays.layoutBounds(px, img.width, img.height)?.let { "npLb" to it }),
            )
            val got = chunks(mine, 0)
            assertEquals("IHDR", got.keys.first(), "$f")
            assertEquals(regions(expected.getValue("npTc")), regions(got.getValue("npTc")), "npTc of $f")
            assertContentEquals(expected["npLb"], got["npLb"], "npLb of $f")
            val back = ImageIO.read(mine.inputStream())
            val w = img.width - 2
            val h = img.height - 2
            assertContentEquals(img.getRGB(1, 1, w, h, null, 0, w), back.getRGB(0, 0, w, h, null, 0, w), "pixels of $f")
        }
        println("${patches.size} 9-patches as aapt2 compiles them")
    }
}
