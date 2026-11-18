package dev.bold7.firmware

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.system.exitProcess

/** The official files of the Bold 9900 Bold7 reads, recognised by the sha256 of the whole file. */
enum class Firmware(val sha256: String, val label: String, val fileName: String, val size: Long) {
    LOADER("4f68dd201def6f3f5c648ce166e7e0e5ff713c79f55f97cb777b8aafa8b85adb", "Bold 9900 OS Loader 7.1.0.2807 (OS 7.1.0.1033)",
        "9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe", 273_797_496),
    SIMULATOR("79208dc1036487b05a1be007063bc377a649f4d9a973e58bade2ac06878474f1", "BlackBerry Simulator 7.1.0.318 for the 9900",
        "BlackBerry Simulator_7.1.0.318_9900.exe", 240_527_751),
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
    private val dictionaries: File? = null,
    private val unpacker: (Firmware) -> Unpacker,
) {
    /**
     * [iconTable]: core/icons' bbos7_icons.tsv, whose sources the icones block copies;
     * [soundTable]: system/module's sounds.tsv, whose sources the sons block copies.
     */
    constructor(iconTable: String, soundTable: String, dictionaries: File? = null) :
        this(iconTable, soundTable, Firmware.entries.associateBy { it.sha256 }, dictionaries, ::unpackerOf)

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
            val assets = Assets(File(partial, "firmware"), File(partial, "gen"), iconTable, soundTable, dictionaries)
            assets.all { progress.step("Preparing the images", 0.9f + 0.1f * it) }
            File(work, BootScreen.LOGO).takeIf { it.isFile }?.let { put(File(partial, "gen"), BootScreen.LOGO, it.readBytes()) }
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
 * [dir], then its folders, one level down: a zip of the 9900's file unpacked on the phone leaves it
 * in Download/<the zip's name>/ (as the icons pack, system/module/src/icons.sh).
 */
private fun lookedIn(dir: File): List<File> = listOf(dir) + dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }

/** The files of [dir] and its folders that have the size of a known [Firmware]: only those are worth hashing. */
internal fun candidates(dir: File): List<File> = lookedIn(dir).flatMap { d ->
    d.listFiles { f -> f.isFile && Firmware.entries.any { it.size == f.length() } }.orEmpty().sortedBy { it.name }
}

/** The files of [dir] and its folders named like a known [Firmware] but not of its size: a partial download, most likely. */
internal fun misnamed(dir: File): List<File> = lookedIn(dir).flatMap { d ->
    d.listFiles { f -> f.isFile && Firmware.entries.any { it.fileName == f.name && it.size != f.length() } }.orEmpty().sortedBy { it.name }
}

internal fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 20)
    file.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private val wanted get() = Firmware.entries.joinToString(" or ") { it.fileName }

/**
 * `CLASSPATH=<jar> app_process /system/bin dev.bold7.firmware.ExtractorKt <command>`, for the
 * module's extract.sh, whose output the KernelSU manager shows the user while Bold7 installs:
 *
 * - `find <folder>`: the path of the official file of the 9900 in [folder] (the user's Download/),
 *   found by size then sha256; a file of the right size or name that is not it is named and
 *   explained on the error output;
 * - `extract <file> <output dir> <bbos7_icons.tsv> <sounds.tsv> [<dictionary dir>]`: [Extractor.extract],
 *   which hashes its own private copy again before any parser sees it, and prints how far it is at
 *   most every [PROGRESS_MS]; the dictionary dir holds Typing's AOSP lists, for OS 7's part of them;
 * - `layer <gen dir of an extraction> <phone's stock fonts.xml> <layer dir>`: [Layer.lay], then what
 *   it laid ("2,341 images, 48 sounds, 23 fonts");
 * - `help <OS Loader> <output dir>`: for the development build only (tools/import-assets.sh help),
 *   the help as [Assets.help] makes it, from the help and theme modules alone.
 *
 * Exit code 0 when done, 1 with the reason on the error output, 2 for a wrong command line, 3 when
 * find found no official file.
 */
fun main(args: Array<String>) {
    exitProcess(
        try {
            when {
                args.size == 2 && args[0] == "find" -> find(File(args[1]))
                args.size in 5..6 && args[0] == "extract" ->
                    extract(File(args[1]), File(args[2]), File(args[3]).readText(), File(args[4]).readText(), args.getOrNull(5)?.let(::File))
                args.size == 3 && args[0] == "help" -> help(File(args[1]), File(args[2]))
                args.size == 4 && args[0] == "layer" -> {
                    println(Layer.lay(File(args[1]), File(args[2]).readText(), File(args[3])))
                    0
                }
                else -> {
                    System.err.println("usage: find <folder> | extract <file> <output dir> <bbos7_icons.tsv> <sounds.tsv> [<Typing's dictionary dir>] | layer <gen dir> <fonts.xml> <layer dir> | help <OS Loader> <output dir>")
                    2
                }
            }
        } catch (e: Exception) { // whatever stops it, the KernelSU manager's log must say why
            System.err.println(e.message ?: e.toString())
            1
        },
    )
}

private fun find(dir: File): Int {
    for (f in candidates(dir)) {
        if (sha256(f) in Firmware.entries.map { it.sha256 }) {
            println(f.path)
            return 0
        }
        System.err.println("${f.name} in your Download folder is not the official file: its fingerprint is not the one Bold7 knows. Download $wanted again.")
    }
    for (f in misnamed(dir)) System.err.println("${f.name} in your Download folder is incomplete (${f.length()} bytes): download it again.")
    return 3
}

private fun help(input: File, output: File): Int {
    if (sha256(input) != Firmware.LOADER.sha256) throw UnknownFirmwareException(sha256(input))
    val work = File(output, ".help-work")
    work.deleteRecursively()
    try {
        // The unpacker deletes the file it is handed: a private copy, as in Extractor.extract.
        val copy = File(work, "input").also { it.parentFile.mkdirs(); input.copyTo(it) }
        LoaderUnpacker(only = { it == "$HELP.cod" || it in THEME }).unpack(copy, work, File(work, "tree")) {}
        File(output, "help").deleteRecursively()
        val assets = Assets(File(work, "tree"), output, "", "")
        assets.help()
        if (assets.skipped.isNotEmpty()) throw IOException("no help in ${input.name}")
        println("help: %d pages in %s".format(File(output, "help").list { _, n -> n.endsWith(".html") }!!.size, File(output, "help")))
    } finally {
        work.deleteRecursively()
    }
    return 0
}

private const val PROGRESS_MS = 3000L

private fun extract(input: File, output: File, iconTable: String, soundTable: String, dictionaries: File?): Int {
    var last = 0L
    val firmware = Extractor(iconTable, soundTable, dictionaries).extract(input, output) { _, fraction ->
        val now = System.nanoTime() / 1_000_000
        if (now - last >= PROGRESS_MS) {
            if (last != 0L) println("      ${(fraction * 100).toInt()}%")
            last = now
        }
    }
    println("      from ${firmware.label}")
    return 0
}
