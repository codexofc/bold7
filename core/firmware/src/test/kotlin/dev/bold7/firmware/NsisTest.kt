package dev.bold7.firmware

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** NSIS's deflate and installer layout, on fabricated data (the real one: RealFirmwareTest). */
class NsisTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun deflate(b: ByteArray, level: Int): ByteArray {
        val d = Deflater(level, true)
        d.setInput(b)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** A stored block as NSIS's zlib writes it: header bits, then the length without its complement. */
    private fun stored(data: ByteArray, last: Boolean) =
        byteArrayOf(if (last) 1 else 0, data.size.toByte(), (data.size shr 8).toByte()) + data

    private val text = buildString { for (i in 0 until 4000) append("net_rim_theme_").append(i * 7919 % 613).append(';') }.toByteArray()

    @Test
    fun inflatesWhatZlibDeflates() {
        // Fixed codes for the short input, dynamic ones for the long one, long matches and distances.
        for (data in listOf("abcabcabcabd".toByteArray(), text)) for (level in listOf(1, 6, 9))
            assertContentEquals(data, NsisInflate.inflate(deflate(data, level), 1 shl 20))
    }

    @Test
    fun storedBlocksHaveNoComplement() {
        val a = ByteArray(300) { (it * 13).toByte() }
        val b = ByteArray(5) { it.toByte() }
        assertContentEquals(a + b, NsisInflate.inflate(stored(a, last = false) + stored(b, last = true), 1000))
    }

    @Test
    fun refusesTruncatedOrOversizedData() {
        val z = deflate(text, 9)
        assertFailsWith<IOException> { NsisInflate.inflate(z.copyOf(z.size / 2), 1 shl 20) }
        assertFailsWith<IOException> { NsisInflate.inflate(z, text.size - 1) }
        assertFailsWith<IOException> { NsisInflate.inflate(stored(ByteArray(10), last = true).copyOf(8), 100) }
        assertFailsWith<IOException> { NsisInflate.inflate(byteArrayOf(0x07), 100) } // block type 3
    }

    private fun le(vararg ints: Int): ByteArray = ByteBuffer.allocate(4 * ints.size).order(ByteOrder.LITTLE_ENDIAN).apply { ints.forEach { putInt(it) } }.array()

    /**
     * An installer: a 1024-byte stub, the first header, the script's header (deflated), then the
     * files; the script sets `$_OUTDIR\Java` then extracts [names] (a.cod, then the others), then
     * sets `$INSTDIR\Other` and extracts other.cod.
     */
    private fun installer(names: List<String>, files: List<ByteArray>): java.io.File {
        val strings = ByteArrayOutputStream()
        fun str(s: String, varIndex: Int = -1): Int {
            val at = strings.size()
            if (varIndex >= 0) strings.write(byteArrayOf(253.toByte(), ((varIndex and 0x7F) or 0x80).toByte(), ((varIndex shr 7) or 0x80).toByte()))
            strings.write(s.toByteArray(Charsets.ISO_8859_1)); strings.write(0)
            return at
        }
        val data = ByteArrayOutputStream()
        val offsets = files.map { f -> data.size().also { data.write(le(Int.MIN_VALUE or (f.size + 3))); data.write(stored(f, last = true)) } }
        val entries = ArrayList<IntArray>()
        entries += intArrayOf(11, str("\\Java", 31), 1, 0, 0, 0, 0)
        names.forEachIndexed { i, n -> entries += intArrayOf(20, 0, str(n), offsets[i], 0, 0, 0) }
        entries += intArrayOf(11, str("\\Other", 21), 1, 0, 0, 0, 0)
        entries += intArrayOf(20, 0, str("other.cod"), offsets[0], 0, 0, 0)
        val entriesAt = 4 + 8 * 8
        val stringsAt = entriesAt + 28 * entries.size
        val header = ByteArrayOutputStream()
        header.write(le(0, 0, 0, 0, 0, entriesAt, entries.size, stringsAt, strings.size(), 0, 0, 0, 0, 0, 0, 0, 0))
        for (e in entries) header.write(le(*e))
        header.write(strings.toByteArray())
        val h = header.toByteArray()
        val packed = deflate(h, 9)
        val out = ByteArrayOutputStream()
        out.write(ByteArray(1024))
        out.write(le(0, 0xDEADBEEF.toInt()))
        out.write("NullsoftInst".toByteArray())
        out.write(le(h.size, 0))
        out.write(le(Int.MIN_VALUE or packed.size))
        out.write(packed)
        out.write(data.toByteArray())
        return tmp.newFile().also { it.writeBytes(out.toByteArray()) }
    }

    @Test
    fun listsTheScriptsFilesAndReadsThem() {
        val a = "first".toByteArray()
        val b = ByteArray(2000) { (it % 251).toByte() }
        RandomAccessFile(installer(listOf("a.cod", "b.cod"), listOf(a, b)), "r").use { f ->
            val nsis = Nsis.open(f)
            assertEquals(listOf("\$<253:31>\\Java/a.cod", "\$<253:31>\\Java/b.cod", "\$<253:21>\\Other/other.cod"), nsis.files.map { "${it.dir}/${it.name}" })
            assertContentEquals(a, Nsis.read(f, nsis, nsis.files[0], 100))
            assertContentEquals(b, Nsis.read(f, nsis, nsis.files[1], 2000))
            assertFailsWith<IOException> { Nsis.read(f, nsis, nsis.files[1], 1999) }
            assertFailsWith<IOException> { Nsis.read(f, nsis, Nsis.Entry("", "x", 1L shl 40), 100) }
        }
    }

    @Test
    fun aCodNameLeavingTheTreeIsRefused() {
        val file = installer(listOf("..\\evil.cod"), listOf(ByteArray(4)))
        val e = assertFailsWith<IOException> { SimulatorUnpacker().unpack(file, tmp.newFolder(), tmp.newFolder()) {} }
        assertEquals("unsafe .cod name ..\\evil.cod", e.message)
    }

    @Test
    fun notAnInstallerIsRefused() {
        assertFailsWith<IOException> { RandomAccessFile(tmp.newFile().also { it.writeBytes(ByteArray(4096)) }, "r").use { Nsis.open(it) } }
    }
}
