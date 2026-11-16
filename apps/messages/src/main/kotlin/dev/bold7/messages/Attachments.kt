package dev.bold7.messages

// A text's attachments: what Attach takes, how it fits the carrier's MMS limit,
// what a bubble's attachment opens. Pure; AndroidAttachmentStore copies and shrinks, the
// activity picks and shows.

/** Attach's submenu (guide 7.1 p. 104, B: "Click an attachment type"); its entries, D. */
enum class AttachKind { PICTURE, VIDEO, AUDIO, FILE }

/** An attachment in the field, copied into Messages' own storage: [uri] is AttachmentProvider's. */
data class Attachment(val uri: String, val type: String, val name: String, val size: Long)

/** The carrier's MMS limits (SmsManager's carrier config): the whole message, and a picture's sides. */
data class MmsLimits(val bytes: Int, val width: Int, val height: Int) {
    /** What an attachment may weigh: the limit, less the headers, the SMIL and the text (D). */
    val budget: Int get() = (bytes - MMS_OVERHEAD).coerceAtLeast(0)

    companion object {
        /** AOSP's defaults (MmsConfig), when the carrier config cannot be read. */
        val DEFAULT = MmsLimits(300 * 1024, 640, 480)
    }
}

/** Kept for the PDU headers, the SMIL and a text (D): 8 KiB. */
const val MMS_OVERHEAD = 8 * 1024

/** The smallest side a picture is shrunk to before giving up (D). */
const val MIN_SIDE = 160

/**
 * What a text takes ("Verify that the file is a .vcf (vCard) file, an .ics (iCalendar) file, or a
 * media file", guide 7.1 p. 130, B).
 */
fun attachable(type: String): Boolean {
    val t = type.lowercase()
    return t.substringBefore('/') in MEDIA && t.substringAfter('/', "").isNotEmpty() || t in CARDS
}

private val MEDIA = setOf("image", "video", "audio")
private val CARDS = setOf("text/x-vcard", "text/vcard", "text/calendar", "text/x-vcalendar")

/** The types the document picker shows for [kind]. */
fun pickerTypes(kind: AttachKind): List<String> = when (kind) {
    AttachKind.PICTURE -> listOf("image/*")
    AttachKind.VIDEO -> listOf("video/*")
    AttachKind.AUDIO -> listOf("audio/*")
    AttachKind.FILE -> listOf("image/*", "video/*", "audio/*") + CARDS
}

enum class Fit { KEEP, SHRINK, TOO_LARGE }

/**
 * Whether a [type] of [size] bytes (-1: unknown) and, for a picture, [width] by [height] goes in
 * a text as it is: a picture too heavy or larger than the carrier's sides is shrunk (OS 7 sent
 * pictures at the carrier's size, D: no source of how); anything else too heavy is refused.
 */
fun fit(type: String, size: Long, width: Int, height: Int, limits: MmsLimits): Fit {
    val light = size in 0..limits.budget.toLong()
    if (!type.lowercase().startsWith("image/")) return if (light) Fit.KEEP else Fit.TOO_LARGE
    val small = maxOf(width, height) <= maxOf(limits.width, limits.height) && minOf(width, height) <= minOf(limits.width, limits.height)
    return if (light && small) Fit.KEEP else Fit.SHRINK
}

/**
 * The sides a picture of [width] by [height] is tried at, largest first: within the carrier's
 * sides either way up, never enlarged, then three quarters each time down to [MIN_SIDE] (D).
 */
fun shrinkSteps(width: Int, height: Int, limits: MmsLimits): List<Pair<Int, Int>> {
    val long = maxOf(width, height).coerceAtLeast(1)
    val short = minOf(width, height).coerceAtLeast(1)
    var scale = minOf(1.0, maxOf(limits.width, limits.height).toDouble() / long, minOf(limits.width, limits.height).toDouble() / short)
    val steps = ArrayList<Pair<Int, Int>>()
    while (true) {
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        steps += w to h
        if (maxOf(w, h) * 3 / 4 < MIN_SIDE) return steps
        scale *= 0.75
    }
}

/** A file name safe in a path and a URI: letters, digits, dot, dash and underscore; 64 characters at most, its extension kept. */
fun safeName(name: String): String {
    val clean = name.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_' }.joinToString("")
    if (clean.all { it == '.' }) return "attachment"
    if (clean.length <= NAME_MAX) return clean
    val ext = clean.substringAfterLast('.', "").takeIf { it.length in 1..8 }?.let { ".$it" }.orEmpty()
    return clean.take(NAME_MAX - ext.length) + ext
}

private const val NAME_MAX = 64

/** The theme's attachment_*.png an attachment is drawn with (A). */
enum class AttachIcon { PICTURE, VIDEO, MUSIC, CONTACT, CALENDAR, UNKNOWN }

fun iconOf(type: String): AttachIcon {
    val t = type.lowercase()
    return when {
        t.startsWith("image/") -> AttachIcon.PICTURE
        t.startsWith("video/") -> AttachIcon.VIDEO
        t.startsWith("audio/") -> AttachIcon.MUSIC
        t == "text/x-vcard" || t == "text/vcard" -> AttachIcon.CONTACT
        t == "text/calendar" || t == "text/x-vcalendar" -> AttachIcon.CALENDAR
        else -> AttachIcon.UNKNOWN
    }
}

/** What a bubble's attachment opens: a picture in the viewer, anything else in the app that plays it. */
sealed interface Opens {
    data class Picture(val uri: String) : Opens
    data class Media(val uri: String, val type: String) : Opens
}

/** A bubble opens its first picture, else its first other part; null: nothing to open. */
val ThreadMessage.opens: Opens?
    get() = images.firstOrNull()?.let(Opens::Picture) ?: media.firstOrNull()?.let { Opens.Media(it.uri, it.type) }
