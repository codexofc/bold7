package dev.bold7.firmware

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BootScreen] on screens made here (its run-length code, the OS image's records, its checks),
 * then on the real OS Loader and simulator when assets-private/firmware/ has them.
 */
class BootScreenTest {
    private val root = File(System.getProperty("bold7.root") ?: "../..")

    /** RGB565 of an opaque ARGB colour, as the screen stores it. */
    private fun rgb565(argb: Int) = ((argb shr 19 and 0x1F) shl 11) or ((argb shr 10 and 0x3F) shl 5) or (argb shr 3 and 0x1F)

    /** A boot screen: black, a grey box where the logo goes, the bar's track. */
    private fun screen(logo: Int = 0x7BEF, outside: Int = 0): IntArray {
        val px = IntArray(640 * 480)
        for (y in 190 until 190 + 59) for (x in 154 until 154 + 332) px[y * 640 + x] = if (x == 154 || y == 190 || x == 485 || y == 248) logo else 0
        for (y in 435..440) for (x in 54..585) px[y * 640 + x] = 0x3186
        px[100 * 640 + 20] = outside
        return px
    }

    /** The screen's code: runs of at most 127 of one pixel, literal groups of at most 127. */
    private fun encode(px: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < px.size) {
            var n = 1
            while (i + n < px.size && n < 127 && px[i + n] == px[i]) n++
            if (n > 1) {
                out.write(n); out.write(px[i] and 0xFF); out.write(px[i] shr 8)
            } else {
                var m = 1
                while (i + m < px.size && m < 127 && px[i + m] != px[i + m - 1]) m++
                out.write(0x80 or m)
                for (k in 0 until m) { out.write(px[i + k] and 0xFF); out.write(px[i + k] shr 8) }
                n = m
            }
            i += n
        }
        return out.toByteArray()
    }

    /** [data] cut into the OS image's records: address, length, type 0x20 and a checksum, then the bytes. */
    private fun records(data: ByteArray, size: Int, address: Int = 0x4AB57DD0): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < data.size) {
            val n = minOf(size, data.size - at)
            val a = address + at
            for (v in intArrayOf(a, n)) for (k in 0..3) out.write(v ushr (8 * k))
            out.write(0x20); out.write(0); out.write(0x5A); out.write(0xA5)
            out.write(data, at, n)
            at += n
        }
        return out.toByteArray()
    }

    private val header = byteArrayOf(-1, -1, -1, -1, 9, 0, 0x80.toByte(), 2, 0xE0.toByte(), 1, 0x18, 2)

    private fun noise(n: Int) = ByteArray(n) { (it * 37 + 11).toByte() }

    @Test
    fun logoIsTheBoxOfTheScreenInTheSimulatorsDll() {
        val logo = BootScreen.logo(noise(5000) + encode(screen()) + noise(3000))
        assertEquals(332 to 59, logo.width to logo.height)
        // 0x7BEF: 5-bit 15 and 6-bit 31, widened by repeating their top bits.
        assertEquals(0xFF7B7D7B.toInt(), logo[0, 0])
        assertEquals(0xFF000000.toInt(), logo[1, 1])
        assertEquals(0xFF7B7D7B.toInt(), logo[331, 58])
    }

    @Test
    fun logoIsReadAcrossTheRecordsOfTheOsImage() {
        // The header, then a code longer than one record: the record headers fall inside it.
        val logo = BootScreen.logo(noise(4000) + records(noise(700) + header + encode(screen()) + noise(500), 0x3FF0) + noise(100))
        assertEquals(0xFF7B7D7B.toInt(), logo[331, 0])
    }

    @Test
    fun aScreenWithSomethingOutsideTheBoxIsRefused() {
        assertFailsWith<IOException> { BootScreen.logo(encode(screen(outside = 0xFFFF))) }
    }

    @Test
    fun aBoxThatIsNotFilledToItsEdgesIsRefused() {
        assertFailsWith<IOException> { BootScreen.logo(encode(screen(logo = 0))) }
    }

    @Test
    fun aFileWithoutTheScreenHasNoLogo() {
        assertNull(BootScreen.logoOrNull(noise(200_000)))
    }

    // The real files: the OS image of the loader, the simulator's fledge9900.dll.

    private val loader = File(root, "assets-private/firmware/bbos7-9900/9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe")
    private val simulator = File(root, "assets-private/firmware/bbos7-simulateur/BlackBerry Simulator_7.1.0.318_9900.exe")

    private fun osImage(): ByteArray {
        var sfi: ByteArray? = null
        RandomAccessFile(loader, "r").use { f ->
            val entry = InstallShield.entries(f, InstallShield.find(f)).first { it.name == "BlackBerry 9900.msi" }
            val msi = File.createTempFile("loader", ".msi")
            try {
                msi.outputStream().buffered(1 shl 16).use { out -> EntryReader(InstallShield::extract).extract(f, entry, out) { } }
                RandomAccessFile(msi, "r").use { m ->
                    val db = Msi(m)
                    val key = db.table("File").single { it["Component_"] == "OS" }["File"] as String
                    Cabinet.extract(db.cf.source(db.stream("Data1.cab")), { it == key }, { }) { _, data -> sfi = data }
                }
            } finally {
                msi.delete()
            }
        }
        return sfi!!
    }

    private fun fledge(): ByteArray = RandomAccessFile(simulator, "r").use { f ->
        val nsis = Nsis.open(f)
        Nsis.read(f, nsis, nsis.files.first { it.name == BootScreen.SIMULATOR_DLL }, 64 shl 20)
    }

    /**
     * The loader's logo is the simulator's, pixel for pixel; against the development logo, cut from
     * a capture of the simulator, red and blue are equal and green differs by at most one level (how
     * the capture widened 6 bits to 8), only where the capture shows a pixel.
     */
    @Test
    fun theFilesGiveTheLogoOfTheCapture() {
        assumeTrue("no OS Loader or simulator in assets-private/firmware", loader.isFile && simulator.isFile)
        val a = BootScreen.logo(osImage())
        val b = BootScreen.logo(fledge())
        assertTrue(a.argb.contentEquals(b.argb), "loader and simulator differ")
        val capture = File(root, "assets-gen/bootanim/logo.png")
        assumeTrue("no assets-gen/bootanim/logo.png", capture.isFile)
        val c = Png.decode(capture.readBytes())
        assertEquals(a.width to a.height, c.width to c.height)
        var green = 0
        for (i in a.argb.indices) {
            val p = a.argb[i]; val q = c.argb[i]
            assertEquals(p and 0xFF00FF, q and 0xFF00FF, "red or blue at ${i % a.width}, ${i / a.width}")
            val dg = (q shr 8 and 0xFF) - (p shr 8 and 0xFF)
            assertTrue(dg in 0..1, "green at ${i % a.width}, ${i / a.width}: %06x, capture %06x".format(p and 0xFFFFFF, q and 0xFFFFFF))
            if (dg != 0) green++
        }
        println("boot logo: ${a.width}x${a.height}, ${a.argb.count { it != 0xFF000000.toInt() }} lit pixels, $green a green level under the capture")
    }
}
