package dev.bold7.firmware

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** The gate before any parser: the sha256 of the whole file, and nothing left on a failure. */
class ExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val official = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val known = mapOf(sha(official) to Firmware.LOADER)

    /** The same fake file known as each official one in turn: the loader, then the simulator. */
    private val eachKnown = Firmware.entries.map { mapOf(sha(official) to it) }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun file(bytes: ByteArray) = tmp.newFile().also { it.writeBytes(bytes) }

    private val out get() = File(tmp.root, "out")

    private fun assertNothingLeft() {
        assertFalse(out.exists())
        assertFalse(File(out.path + ".partial").exists())
    }

    /** Fails the test if any parser (InstallShield, MSI, cabinet, .cod) is reached. */
    private val noParser = Unpacker { _, _, _, _ -> fail("a parser was handed an unknown file") }

    @Test
    fun oneByteOffAKnownFileIsRefusedBeforeAnyParser() = eachKnown.forEach { known ->
        for (i in listOf(0, official.size / 2, official.size - 1)) {
            val near = official.copyOf().also { it[i] = (it[i] + 1).toByte() }
            val e = assertFailsWith<UnknownFirmwareException> { Extractor("", "", known) { noParser }.extract(file(near), out) }
            assertEquals(sha(near), e.sha256)
            assertNothingLeft()
        }
        // One byte more or less too.
        assertFailsWith<UnknownFirmwareException> { Extractor("", "", known) { noParser }.extract(file(official + 0), out) }
        assertFailsWith<UnknownFirmwareException> { Extractor("", "", known) { noParser }.extract(file(official.copyOf(official.size - 1)), out) }
        assertNothingLeft()
    }

    @Test
    fun theKnownFileReachesItsParserAsAPrivateCopy() = eachKnown.forEach { known ->
        val input = file(official)
        var seen: File? = null
        var parserOf: Firmware? = null
        val reached = assertFailsWith<IOException> {
            Extractor("", "", known) { fw -> parserOf = fw; Unpacker { f, _, _, _ -> seen = f; assertContentEquals(official, f.readBytes()); throw IOException("reached") } }
                .extract(input, out)
        }
        assertEquals("reached", reached.message)
        assertEquals(known.values.single(), parserOf)
        assertFalse(seen == input, "the parser must read the copy, not the user's file")
        assertNothingLeft()
    }

    @Test
    fun eachShippedFileHasItsParser() {
        assertTrue(Extractor.unpackerOf(Firmware.LOADER) is LoaderUnpacker)
        assertTrue(Extractor.unpackerOf(Firmware.SIMULATOR) is SimulatorUnpacker)
    }

    @Test
    fun theShippedExtractorKnowsOnlyTheTwoFiles() {
        assertFailsWith<UnknownFirmwareException> { Extractor("", "").extract(file(official), out) }
        assertNothingLeft()
    }

    @Test
    fun anInterruptedExtractionLeavesNothing() {
        try {
            assertFailsWith<InterruptedException> {
                Extractor("", "", known) { noParser }.extract(file(official), out) { _, _ -> Thread.currentThread().interrupt() }
            }
        } finally {
            Thread.interrupted()
        }
        assertNothingLeft()
    }
}
