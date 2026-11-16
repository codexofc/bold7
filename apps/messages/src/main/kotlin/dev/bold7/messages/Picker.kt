package dev.bold7.messages

// The file picker behind Attach, pure: the media the phone holds, shown as a tree
// of folders and files. "Click an attachment type. Click a file" (guide 7.1 p. 104, B); the
// guide shows no explorer and no capture does, so the layout is the list's of OS 7 (D).

/** A picture, a video or a sound, [dir] its folder under the volume's root ("DCIM/Camera", "" at the root). */
data class MediaItem(val uri: String, val name: String, val type: String, val size: Long, val dir: String)

/** A row of the picker: a folder with the number of items under it, or a file. */
sealed interface PickerRow {
    data class Folder(val name: String, val path: String, val count: Int) : PickerRow
    data class File(val item: MediaItem) : PickerRow
}

private fun String.under(dir: String) = if (dir.isEmpty()) this.isNotEmpty() else startsWith("$dir/")

/** The rows of [dir]: its folders (those with something under them) by name, then its files by name (D, as a file explorer). */
fun browse(items: List<MediaItem>, dir: String): List<PickerRow> {
    val folders = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
    val files = ArrayList<MediaItem>()
    for (i in items) {
        when {
            i.dir == dir -> files += i
            i.dir.under(dir) -> {
                val rest = if (dir.isEmpty()) i.dir else i.dir.substring(dir.length + 1)
                folders.merge(rest.substringBefore('/'), 1, Int::plus)
            }
        }
    }
    val join = { name: String -> if (dir.isEmpty()) name else "$dir/$name" }
    return folders.map { (n, c) -> PickerRow.Folder(n, join(n), c) } +
        files.sortedBy { it.name.lowercase() }.map { PickerRow.File(it) }
}

/** Where the picker is: a [dir], the row in focus, and the folders it came through with their focus, for Escape. */
data class PickerNav(val dir: String = "", val focus: Int = 0, val back: List<Pair<String, Int>> = emptyList()) {
    fun focused(index: Int) = copy(focus = index)

    /** Into [path], the focus on its first row. */
    fun into(path: String) = PickerNav(path, 0, back + (dir to focus))

    /** Escape: one folder up with the focus where it was; null at the root (the picker closes). */
    fun up(): PickerNav? = back.lastOrNull()?.let { (d, f) -> PickerNav(d, f, back.dropLast(1)) }
}

/** The title: the kind's name at the root, the folder's path inside it. */
fun pickerTitle(kind: String, dir: String) = dir.ifEmpty { kind }

/** A size as a file explorer says it (D): bytes, then KB, MB, GB to one decimal, 1024 apiece. */
fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var v = bytes / 1024.0
    for (unit in listOf("KB", "MB", "GB")) {
        if (v < 1024 || unit == "GB") return "%.1f %s".format(java.util.Locale.US, v, unit)
        v /= 1024
    }
    return ""
}
