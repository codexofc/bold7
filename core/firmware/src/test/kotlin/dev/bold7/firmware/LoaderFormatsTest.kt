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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** InstallShield, MSI names, cabinet and .cod on fabricated data (the model: tools/test_extract_bbos7.py). */
class LoaderFormatsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun le(vararg fields: Pair<Int, Long>): ByteArray {
        val b = ByteBuffer.allocate(fields.sumOf { it.first }).order(ByteOrder.LITTLE_ENDIAN)
        for ((w, v) in fields) when (w) { 1 -> b.put(v.toByte()); 2 -> b.putShort(v.toShort()); else -> b.putInt(v.toInt()) }
        return b.array()
    }

    private fun zlib(b: ByteArray, raw: Boolean = false, dict: ByteArray? = null): ByteArray {
        val d = Deflater(9, raw)
        if (dict != null) d.setDictionary(dict)
        d.setInput(b)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- InstallShield

    /** Inverse of the obfuscation: b = rot4(~p ^ key) = rot4(p ^ ~key), the key restarting every 1024 bytes. */
    private fun isEncode(data: ByteArray, name: String): ByteArray {
        val key = InstallShield.key(name) // already inverted and 1024 bytes long
        return ByteArray(data.size) { i ->
            val v = (data[i].toInt() xor key[i % 1024].toInt()) and 0xFF
            (((v shl 4) or (v ushr 4)) and 0xFF).toByte()
        }
    }

    private fun installShield(files: List<Pair<String, ByteArray>>, decoy: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val sig = "InstallShield\u0000".toByteArray(Charsets.ISO_8859_1)
        if (decoy) { out.write(sig); out.write(ByteArray(32)) }
        out.write(sig); out.write(le(2 to files.size.toLong())); out.write(ByteArray(30))
        for ((name, payload) in files) {
            val enc = isEncode(zlib(payload), name)
            out.write(name.toByteArray().copyOf(260)); out.write(le(4 to 6, 4 to 0, 4 to enc.size.toLong())); out.write(ByteArray(8))
            out.write(le(2 to 1)); out.write(ByteArray(30)); out.write(enc)
        }
        return out.toByteArray()
    }

    private fun decoded(hexIn: String, name: String) = hex(hexIn).also { InstallShield.decodeBlock(it, it.size, InstallShield.key(name)) }

    @Test
    fun installShieldKnownVectors() {
        // Bytes of the 9900 loader: the start of SplashScreen.BMP (a zlib header once decoded), and
        // the start of the second 1024-byte block of 0x0405.ini, whose 10-byte key restarts.
        assertContentEquals(hex("789ceddd01841ed7"), decoded("7c628f44e96243c4", "SplashScreen.BMP"))
        assertContentEquals(hex("1f37e2d14514fc7b594616f4"), decoded("3c58bad199bebaaebd5eac64", "0x0405.ini"))
    }

    @Test
    fun installShieldHeaderAndDecoding() {
        val big = ByteArray(256 * 20) { it.toByte() } // several blocks, a key of 9 bytes
        val file = tmp.newFile()
        file.writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()) + ByteArray(100) +
            installShield(listOf("Setup.ini" to "[Info]\r\n".toByteArray(), "BlackBerry 9900.msi" to big), decoy = true))
        RandomAccessFile(file, "r").use { f ->
            val base = InstallShield.find(f)
            assertEquals(102L + 0x2E, base) // the empty decoy is skipped
            val entries = InstallShield.entries(f, base)
            assertEquals(listOf("Setup.ini", "BlackBerry 9900.msi"), entries.map { it.name })
            for ((e, want) in entries.zip(listOf("[Info]\r\n".toByteArray(), big))) {
                val out = ByteArrayOutputStream()
                InstallShield.extract(f, e, out) {}
                assertContentEquals(want, out.toByteArray())
            }
        }
    }

    @Test
    fun installShieldRefusesAPathInAName() {
        val file = tmp.newFile().also { it.writeBytes(installShield(listOf("../evil" to byteArrayOf(1)))) }
        RandomAccessFile(file, "r").use { f -> assertFailsWith<IOException> { InstallShield.entries(f, 0) } }
    }

    @Test
    fun msiStreamNames() {
        assertEquals("!AB", Msi.msiName("" + 0x4840.toChar() + (0x3800 + 10 + (11 shl 6)).toChar()))
    }

    // ---------------------------------------------------------------- cabinet

    /** A cabinet of one MSZIP folder holding [files], cut in blocks of [block] bytes. */
    private fun cabinet(files: List<Pair<String, ByteArray>>, block: Int = 32768): ByteArray {
        val all = files.fold(ByteArray(0)) { a, f -> a + f.second }
        val blocks = ByteArrayOutputStream()
        var count = 0
        var prev: ByteArray? = null
        for (start in all.indices step block) {
            val chunk = all.copyOfRange(start, minOf(start + block, all.size))
            val data = byteArrayOf('C'.code.toByte(), 'K'.code.toByte()) + zlib(chunk, raw = true, dict = prev)
            blocks.write(le(4 to 0, 2 to data.size.toLong(), 2 to chunk.size.toLong())); blocks.write(data)
            prev = chunk
            count++
        }
        val table = ByteArrayOutputStream()
        var off = 0L
        for ((name, b) in files) {
            table.write(le(4 to b.size.toLong(), 4 to off, 2 to 0, 2 to 0, 2 to 0, 2 to 0x20)); table.write(name.toByteArray() + 0)
            off += b.size
        }
        val filesOff = 36L + 8
        val dataOff = filesOff + table.size()
        val head = le(4 to 0x4643534DL, 4 to 0, 4 to 0, 4 to 0, 4 to filesOff, 4 to 0, 1 to 3, 1 to 1, 2 to 1, 2 to files.size.toLong(), 2 to 0, 2 to 0, 2 to 0)
        return head + le(4 to dataOff, 2 to count.toLong(), 2 to 1) + table.toByteArray() + blocks.toByteArray()
    }

    @Test
    fun cabinetKeepsOnlyTheWantedFilesAcrossBlocks() {
        val a = ByteArray(70_000) { (it % 251).toByte() }
        val b = "short".toByteArray()
        val c = ByteArray(50_000) { (it * 7).toByte() }
        val cab = cabinet(listOf("skip.sfi" to c, "a.cod" to a, "b.cod" to b))
        val got = ArrayList<Pair<String, ByteArray>>()
        var read = 0L
        Cabinet.extract(ArraySource(cab), { it.endsWith(".cod") }, { read += it }) { n, d -> got += n to d }
        assertEquals(listOf("a.cod", "b.cod"), got.map { it.first })
        assertContentEquals(a, got[0].second)
        assertContentEquals(b, got[1].second)
    }

    @Test
    fun cabinetRefusesAFileTooLargeToHold() {
        val cab = cabinet(listOf("a.cod" to ByteArray(10))).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(44, Cabinet.MAX_FILE + 1) }
        assertFailsWith<IOException> { Cabinet.extract(ArraySource(cab), { true }, {}) { _, _ -> } }
    }

    // ---------------------------------------------------------------- COD

    private val png = byteArrayOf(0x89.toByte()) + "PNG\r\n\u001a\n".toByteArray() + "rest-of-png".toByteArray()

    /** A COD module whose static initialiser registers each (names, bytes) resource. */
    private fun codModule(resources: List<Pair<List<String>, ByteArray>>): ByteArray {
        val data = ByteArrayOutputStream().apply { write(ByteArray(8)) }
        fun add(blob: ByteArray, tag: Int): Int {
            while (data.size() % 4 != 0) data.write(0)
            data.write(le(2 to blob.size.toLong(), 2 to tag.toLong()))
            val p = data.size()
            data.write(blob)
            return p
        }
        val code = ByteArrayOutputStream()
        for ((names, blob) in resources) {
            val p = add(blob, 0xD004)
            code.write(byteArrayOf(0x2D, 0x02)); code.write(le(2 to blob.size.toLong(), 2 to p.toLong())); code.write(byteArrayOf(0x56, 0x3F))
            names.forEachIndexed { k, name ->
                val q = add(name.toByteArray(), 0xD824)
                if (k > 0) code.write(0x3F)
                code.write(0x28); code.write(le(2 to q.toLong())); code.write(byteArrayOf(0x40, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x03, 0xCD.toByte()))
            }
        }
        code.write(byteArrayOf(0x1F, 0x00))
        val head = le(4 to 0xFFFFC0DEL, 4 to 0, 4 to 0, 4 to 0, 4 to 0, 4 to 0, 2 to 5, 2 to 0xFFFF, 4 to -1, 4 to -1,
            2 to 79, 2 to code.size().toLong(), 2 to data.size().toLong(), 2 to 1)
        return head + code.toByteArray() + data.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun resourcesOfAModule() {
        val tune = "MThd\u0000\u0000\u0000\u0006".toByteArray()
        val mod = codModule(listOf(listOf("img/icon.png", "icon.png") to png, listOf("tune.mid") to tune))
        assertEquals(listOf("img/icon.png", "icon.png", "tune.mid"), Cod.moduleResources(mod).map { it.first })
        // The name without its path is dropped, the bytes being the same.
        val res = Cod.resources(mod)
        assertEquals(listOf("img/icon.png", "tune.mid"), res.keys.toList())
        assertContentEquals(png, res["img/icon.png"])
    }

    @Test
    fun notAModule() = assertEquals(emptyList(), Cod.moduleResources(ByteArray(64)))

    @Test
    fun chunksOverSiblings() {
        val big = png + ByteArray(256 * 300) { it.toByte() }
        val (a, b) = big.copyOf(40000) to big.copyOfRange(40000, big.size)
        // The order in the zip differs from the numeric one.
        val cod = zip("t-2.cod" to codModule(listOf(listOf("__wall.png@40000") to b)),
            "t.cod" to codModule(listOf(listOf("other.gif") to "GIF89a".toByteArray())),
            "t-1.cod" to codModule(listOf(listOf("__wall.png@0", "wall.png") to a)))
        val res = Cod.resources(cod)
        assertContentEquals(big, res["wall.png"])
        assertContentEquals("GIF89a".toByteArray(), res["other.gif"])
    }

    @Test
    fun aMissingChunkFails() {
        val mod = codModule(listOf(listOf("__a.png@0") to ByteArray(10), listOf("__a.png@20") to byteArrayOf(1)))
        assertFailsWith<IOException> { Cod.resources(mod) }
    }

    @Test
    fun fontsJoined() {
        val res = linkedMapOf("ttf/raw_split/Alpha.001" to byteArrayOf(0, 1, 0, 0) + "head".toByteArray(), "ttf/raw_split/Alpha.002" to "tail".toByteArray(),
            "data/map.001" to "notafont".toByteArray(), "sff/RIM_european.sff4" to "sff4".toByteArray())
        val fonts = Cod.fonts(res)
        assertEquals(setOf("Alpha.ttf", "RIM_european.sff4"), fonts.keys)
        assertContentEquals(byteArrayOf(0, 1, 0, 0) + "headtail".toByteArray(), fonts["Alpha.ttf"])
    }

    @Test
    fun namesThatLeaveTheTreeAreRefused() {
        val root = tmp.newFolder()
        for (bad in listOf("../x", "theme/../../x", "/etc/passwd", "")) assertFailsWith<IOException>(bad) { safeFile(root, bad) }
        assertEquals(java.io.File(root, "theme/b/x.png"), safeFile(root, "theme/b/./x.png"))
    }
}
