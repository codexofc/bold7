package dev.bold7.firmware

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.system.exitProcess

/** The official files of the Bold 9900 Bold7 reads, recognised by the sha256 of the whole file. */
enum class Firmware(val sha256: String, val label: String) {
    LOADER("4f68dd201def6f3f5c648ce166e7e0e5ff713c79f55f97cb777b8aafa8b85adb", "Bold 9900 OS Loader 7.1.0.2807 (OS 7.1.0.1033)"),
    SIMULATOR("79208dc1036487b05a1be007063bc377a649f4d9a973e58bade2ac06878474f1", "BlackBerry Simulator 7.1.0.318 for the 9900"),
}

/** A file that is not one of [Firmware]: refused before any parser sees a byte of it. */
class UnknownFirmwareException(val sha256: String) :
    IOException("unknown file (sha256 $sha256): only the ${Firmware.entries.joinToString(" and ") { it.label }} are known")

/** What an extraction is doing, with its overall fraction (0 to 1). */
fun interface Progress {
    fun report(step: String, fraction: Float)
}

/**
 * Extracts Bold7's assets from the official file of the 9900, without android.*: the module's
 * customize.sh runs [main] in root through app_process.
 *
 * [outputDir] then holds `firmware/` (the tree of tools/extract-bbos7.py, as assets-private/
 * bbos7-9900/) and `gen/` (the layout of assets-gen/, the blocks of tools/import-assets.sh ported
 * so far). It is written as `<outputDir>.partial` and renamed at the end: an extraction that fails
 * or is cancelled (the thread interrupted) leaves nothing.
 */
class Extractor internal constructor(
    private val iconTable: String,
    private val soundTable: String,
    private val known: Map<String, Firmware>,
    private val unpacker: (Firmware) -> Unpacker,
) {
    /**
     * [iconTable]: core/icons' bbos7_icons.tsv, whose sources the icones block copies;
     * [soundTable]: system/module's sounds.tsv, whose sources the sons block copies.
     */
    constructor(iconTable: String, soundTable: String) : this(iconTable, soundTable, Firmware.entries.associateBy { it.sha256 }, ::unpackerOf)

    internal companion object {
        /** The parser of each known file, reached only once its sha256 is matched. */
        fun unpackerOf(firmware: Firmware): Unpacker = when (firmware) {
            Firmware.LOADER -> LoaderUnpacker()
            Firmware.SIMULATOR -> SimulatorUnpacker()
        }
    }

    fun extract(input: File, outputDir: File, progress: Progress = Progress { _, _ -> }): Firmware {
        val partial = File(outputDir.path + ".partial")
        partial.deleteRecursively()
        try {
            val work = File(partial, "work")
            work.mkdirs()
            // The file is copied while it is hashed, and only that private copy is ever parsed:
            // the original, in a folder other apps can write, may change after its hash is taken.
            val copy = File(work, "input")
            val sha = copyHashing(input, copy) { progress.step("Checking ${input.name}", 0.15f * it) }
            val firmware = known[sha] ?: throw UnknownFirmwareException(sha)
            val unpack = unpacker(firmware)
            progress.step("Recognised: ${firmware.label}", 0.15f)
            unpack.unpack(copy, work, File(partial, "firmware")) { progress.step("Unpacking", 0.15f + 0.75f * it) }
            val assets = Assets(File(partial, "firmware"), File(partial, "gen"), iconTable, soundTable)
            assets.all { progress.step("Preparing the images", 0.9f + 0.1f * it) }
            if (assets.skipped.isNotEmpty()) progress.step("Not in this file, drawn by the launcher: ${assets.skipped.joinToString()}", 1f)
            work.deleteRecursively()
            outputDir.deleteRecursively()
            if (!partial.renameTo(outputDir)) throw IOException("cannot rename $partial to $outputDir")
            progress.report("Done", 1f)
            return firmware
        } catch (e: Throwable) {
            partial.deleteRecursively()
            throw e
        }
    }

    private fun Progress.step(step: String, fraction: Float) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("extraction cancelled")
        report(step, fraction)
    }

    private fun copyHashing(input: File, copy: File, progress: (Float) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        input.inputStream().use { i ->
            copy.outputStream().use { o ->
                val total = input.length().coerceAtLeast(1)
                var done = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    o.write(buf, 0, n)
                    done += n
                    progress(minOf(1f, done.toFloat() / total))
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * `app_process -cp <dex> / dev.bold7.firmware.ExtractorKt <file of the 9900> <output dir> <bbos7_icons.tsv> <sounds.tsv>`:
 * one line per step and per percent on the standard output (the KernelSU manager shows it), exit
 * code 0 when done, 1 with the reason on the error output otherwise, 2 for a wrong command line.
 */
fun main(args: Array<String>) {
    if (args.size != 4) {
        System.err.println("usage: <file of the 9900> <output dir> <bbos7_icons.tsv> <sounds.tsv>")
        exitProcess(2)
    }
    var last = ""
    try {
        val firmware = Extractor(File(args[2]).readText(), File(args[3]).readText()).extract(File(args[0]), File(args[1])) { step, fraction ->
            val line = "%3d%% %s".format((fraction * 100).toInt(), step)
            if (line != last) println(line)
            last = line
        }
        println("Extracted from ${firmware.label} into ${args[1]}")
    } catch (e: Exception) { // whatever stops it, the KernelSU manager's log must say why
        System.err.println("Extraction failed: ${e.message ?: e}")
        exitProcess(1)
    }
}
