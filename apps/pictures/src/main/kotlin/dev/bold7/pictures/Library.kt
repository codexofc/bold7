package dev.bold7.pictures

// The pictures of the phone as Pictures of BBOS 7.1 shows them, pure (docs/BBOS7-VISUEL.md §37):
// the categories of its first screen, Camera Pictures then Picture Library then the other folders
// (sim-344, C: Camera Pictures, Picture Library, Wallpaper), each with its count ("0 Items",
// "14 Items"), and the order of the pictures in a category.

/** A picture of MediaStore: [taken] in ms (its date, the bar under the grid shows it), [bucket] its folder's name. */
data class Picture(
    val id: Long,
    val name: String,
    val bucketId: Long,
    val bucket: String,
    val path: String,
    val taken: Long,
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 0,
    val mime: String = "image/jpeg",
    val size: Long = 0,
)

/** What a category holds: the camera's folder, every picture, or one folder of MediaStore. */
sealed interface Source {
    data object Camera : Source
    data object Library : Source
    data class Folder(val bucketId: Long) : Source
}

/** A row of the first screen: its [title] and the pictures it opens. */
data class Category(val source: Source, val title: String, val pictures: List<Picture>)

/** The folders the cameras write to: Android's DCIM/Camera, and Pictures/BenOSCamera, where the Q25's camera saves (read on the Q25). */
private val CAMERA_FOLDERS = setOf("dcim/camera", "pictures/benoscamera")

/** Camera Pictures: what a camera took. */
fun isCamera(p: Picture): Boolean = p.path.trimEnd('/').lowercase() in CAMERA_FOLDERS

/** The order of a category's pictures. */
enum class Sort { NAME, NEWEST, OLDEST }

/**
 * The pictures in [sort]'s order. By name (the default, C: sim-345 shows Wallpaper's 14 pictures
 * from Arch.png on, alphabetically), case ignored; newest or oldest first by date (guide 7.1 p. 13,
 * B: "Sort pictures from either newest to oldest, or oldest to newest"). The id breaks a tie.
 */
fun sorted(pictures: List<Picture>, sort: Sort): List<Picture> = pictures.sortedWith(
    when (sort) {
        Sort.NAME -> compareBy<Picture> { it.name.lowercase() }.thenBy { it.id }
        Sort.NEWEST -> compareByDescending<Picture> { it.taken }.thenByDescending { it.id }
        Sort.OLDEST -> compareBy<Picture> { it.taken }.thenBy { it.id }
    },
)

/**
 * The first screen's categories: Camera Pictures and Picture Library always, even empty (sim-344,
 * C: "0 Items"), then every other folder that has a picture, by name (D). Picture Library holds every
 * picture but the camera's (sim-366, C: one picture taken, Camera Pictures says "1 Item" and Picture
 * Library "0 Items"; on the 9900 it is the pictures folder of the device and of the card).
 */
fun categories(all: List<Picture>, sort: Sort, cameraTitle: String, libraryTitle: String): List<Category> {
    val folders = all.filterNot(::isCamera).groupBy { it.bucketId }.values
        .map { Category(Source.Folder(it.first().bucketId), it.first().bucket, sorted(it, sort)) }
        .sortedWith(compareBy<Category> { it.title.lowercase() }.thenBy { (it.source as Source.Folder).bucketId })
    return listOf(
        Category(Source.Camera, cameraTitle, sorted(all.filter(::isCamera), sort)),
        Category(Source.Library, libraryTitle, sorted(all.filterNot(::isCamera), sort)),
    ) + folders
}

/** The pictures of [source] in [all], in [sort]'s order: what the grid and the viewer show. */
fun picturesOf(all: List<Picture>, source: Source, sort: Sort): List<Picture> = sorted(
    when (source) {
        Source.Camera -> all.filter(::isCamera)
        Source.Library -> all.filterNot(::isCamera)
        is Source.Folder -> all.filter { it.bucketId == source.bucketId && !isCamera(it) }
    },
    sort,
)

/** A row's count, "0 Items", "14 Items" (sim-344, C); "1 Item" (D). */
fun items(n: Int): String = if (n == 1) "1 Item" else "$n Items"

/** The day of [ms] as the simulator writes it, day/month/year without zeros (sim-345: 1/3/2006). */
fun day(ms: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }
    return "${c.get(java.util.Calendar.DAY_OF_MONTH)}/${c.get(java.util.Calendar.MONTH) + 1}/${c.get(java.util.Calendar.YEAR)}"
}
