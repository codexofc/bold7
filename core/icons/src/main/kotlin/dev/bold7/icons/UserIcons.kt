package dev.bold7.icons

// Icons the user provides himself, pure: a file `<package>.png` in /sdcard/Bold7/icons replaces
// the generated icon of that app (an old logo, say, which Bold7 itself never ships: the user's own
// file, as the 9900's firmware). The launcher lists the folder off the main thread and draws.

/** The folder, under the shared storage's root (/sdcard). */
const val USER_ICON_DIR = "Bold7/icons"

/** Where an app's icon comes from, the first that can answer wins. */
enum class IconSource { TABLE, USER, GENERATED }

/** The firmware's OS 7 icon when the table has one, then the user's file, then the generated icon. */
fun iconSource(inTable: Boolean, userIcon: Boolean): IconSource = when {
    inTable -> IconSource.TABLE
    userIcon -> IconSource.USER
    else -> IconSource.GENERATED
}

private val PACKAGE = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

/** The package a file of the folder names, "com.whatsapp" for "com.whatsapp.png"; null for any other file. */
fun userIconPackage(fileName: String): String? =
    fileName.removeSuffix(".png").takeIf { it != fileName && PACKAGE.matches(it) }

/** A file of the folder as listed: its name, size and modification time. */
data class UserIconFile(val name: String, val length: Long, val modified: Long)

/** What tells a replaced file from the same one. */
data class Stamp(val length: Long, val modified: Long)

/** The folder's icons by package. */
fun userIconListing(files: List<UserIconFile>): Map<String, Stamp> =
    files.mapNotNull { f -> userIconPackage(f.name)?.let { it to Stamp(f.length, f.modified) } }.toMap()

/** The packages whose file appeared, changed or went between two listings: their icons are drawn again. */
fun changedPackages(before: Map<String, Stamp>, after: Map<String, Stamp>): Set<String> =
    (before.keys + after.keys).filterTo(HashSet()) { before[it] != after[it] }

/** True for an image of a grid icon's size, 92x92 (A): already an OS 7 icon, shown as it is. */
fun shownAsIs(width: Int, height: Int): Boolean = width == TEMPLATE_SIZE && height == TEMPLATE_SIZE

/** BitmapFactory's inSampleSize for a [width] x [height] image: halved while still twice the icon's size or more. */
fun sampleSize(width: Int, height: Int): Int {
    var s = 1
    while (minOf(width, height) / (s * 2) >= 2 * TEMPLATE_SIZE) s *= 2
    return s
}
