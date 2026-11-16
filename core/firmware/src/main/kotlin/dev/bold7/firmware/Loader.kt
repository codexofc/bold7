package dev.bold7.firmware

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Paths

/**
 * Unpacks a recognised file of the 9900 into the firmware tree, the layout tools/extract-bbos7.py
 * gives assets-private/bbos7-9900/: theme/<cod>/ (every resource of the default theme), images/,
 * sounds/, fonts/ and boot/ <cod>/ for the other modules. Only ever called on a file whose sha256
 * is known ([Extractor]).
 */
internal fun interface Unpacker {
    fun unpack(file: File, work: File, tree: File, progress: (Float) -> Unit)
}

/** The OS Loader: InstallShield, then the MSI and its cabinet, then each .cod of CodFiles. */
internal class LoaderUnpacker(private val reader: EntryReader = EntryReader(InstallShield::extract)) : Unpacker {
    override fun unpack(file: File, work: File, tree: File, progress: (Float) -> Unit) {
        work.mkdirs()
        val msi = File(work, "loader.msi")
        RandomAccessFile(file, "r").use { f ->
            val entry = InstallShield.entries(f, InstallShield.find(f)).firstOrNull { it.name == MSI }
                ?: throw IOException("no $MSI in the loader")
            var read = 0L
            msi.outputStream().buffered(1 shl 16).use { out ->
                reader.extract(f, entry, out) { n -> read += n; progress(0.3f * read / entry.size) }
            }
        }
        file.delete() // the private copy: the MSI holds all that follows
        RandomAccessFile(msi, "r").use { f ->
            val db = Msi(f)
            // The cabinet's entries are the File table's keys; CodFiles holds the .cod modules.
            val cods = db.table("File").filter { it["Component_"] == "CodFiles" }
                .associate { it["File"] as String to (it["FileName"] as String).substringAfterLast('|') }
            val cab = db.cf.source(db.stream("Data1.cab"))
            var read = 0L
            Cabinet.extract(cab, { it in cods }, { n -> read += n; progress(0.3f + 0.7f * read / cab.size) }) { key, data ->
                writeCod(cods.getValue(key), data, tree)
            }
        }
        msi.delete()
    }

    private companion object {
        const val MSI = "BlackBerry 9900.msi"
    }
}

private val THEME = setOf("net_rim_theme_BlackBerry7_640x480_b.cod", "net_rim_theme_BlackBerry7_640x480_bs1.cod")
private val KINDS = mapOf(
    "images" to setOf("png", "gif", "jpg", "jpeg", "bmp"),
    "sounds" to setOf("m4a", "mp3", "mid", "midi", "wav", "amr", "aac", "ogg"),
    "boot" to setOf("mp4"),
)
private val LOCALISED = Regex("__[a-z]{2}(_[A-Za-z]{2,4})?\\.cod$")

/** What tools/extract-bbos7.py writes of one .cod, from the loader as from the simulator. */
internal fun writeCod(cod: String, data: ByteArray, tree: File) {
    val res = Cod.resources(data)
    val stem = cod.removeSuffix(".cod")
    if (cod in THEME) {
        for ((n, b) in res) put(tree, "theme/$stem/$n", b)
        return
    }
    for ((n, b) in Cod.fonts(res)) put(tree, "fonts/$stem/$n", b)
    for ((kind, exts) in KINDS) {
        if (kind == "images" && LOCALISED.containsMatchIn(cod) && !cod.endsWith("__en.cod")) continue
        for ((n, b) in res) {
            if (n.lowercase().substringAfterLast('.') in exts && (kind != "boot" || "video" in cod)) put(tree, "$kind/$stem/$n", b)
        }
    }
}

/** [rel] under [root], refused if it would leave it: names come from the file being read. */
internal fun safeFile(root: File, rel: String): File {
    val p = Paths.get(rel).normalize()
    if (rel.isEmpty() || '\u0000' in rel || p.isAbsolute || p.startsWith("..") || p.toString().isEmpty())
        throw IOException("unsafe path $rel")
    return File(root, p.toString())
}

internal fun put(root: File, rel: String, data: ByteArray) {
    val f = safeFile(root, rel)
    f.parentFile.mkdirs()
    f.writeBytes(data)
}
