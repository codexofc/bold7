package dev.bold7.launcher.search

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.provider.CalendarContract.Instances
import android.provider.CallLog.Calls
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.PhoneLookup
import android.provider.MediaStore
import android.provider.MediaStore.Audio.AudioColumns as A
import android.provider.MediaStore.Files.FileColumns as F
import android.provider.Telephony
import android.util.Size
import dev.bold7.showcase.avatar
import dev.bold7.showcase.isAvatar
import dev.bold7.showcase.showcaseOn
import java.time.ZoneId

data class Contact(val id: Long, val lookupKey: String, val name: String, val photo: String?)

/** A text message, SMS or MMS, from or to [address] ([name] if a contact has it). */
data class Message(val address: String, val name: String?, val body: String, val date: Long)

/** An entry of the call log; [type] is CallLog.Calls.TYPE. */
data class Call(val number: String, val name: String?, val type: Int, val date: Long)

data class Event(val id: Long, val title: String, val begin: Long, val location: String, val allDay: Boolean)

/** A file of MediaStore: picture, video, song or document. */
data class Media(val uri: Uri, val title: String, val detail: String, val mime: String?, val date: Long)

/** What a source found for one query: the first [MAX_RESULTS], and how many in all. */
data class Found(val items: List<Any>, val total: Int)

private val NONE = Found(emptyList(), 0)

/** Rows of [c], [MAX_READ] at most, each made by [row], those it refuses (null) left out. */
private fun <T : Any> Cursor.rows(row: (Cursor) -> T?): List<T> {
    val out = ArrayList<T>()
    var read = 0
    while (read++ < MAX_READ && moveToNext()) row(this)?.let(out::add)
    return out
}

internal fun <T : Any> found(items: List<T>) = Found(items.take(MAX_RESULTS), items.size)

/**
 * The providers the search reads, each with the permission tools/install.sh grants once, or the
 * all files access of the priv-app: refused, its category stays empty, nothing is asked on screen. Every call blocks: the search
 * thread only, canceled at the next key. Nothing is kept past [forget], nothing is logged.
 */
internal class Sources(private val context: Context) {
    private val resolver = context.contentResolver

    /** The contact names of the numbers already seen, for one opening of the search. */
    private val names = HashMap<String, String?>()

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun forget() = names.clear()

    fun find(category: Category, query: String, cancel: CancellationSignal): Found =
        findIn(showcaseOn(context), category, query, System.currentTimeMillis(), ZoneId.systemDefault()) { findReal(category, query, cancel) }

    private fun findReal(category: Category, query: String, cancel: CancellationSignal): Found = when (category) {
        Category.CONTACTS -> contacts(query, cancel)
        Category.CALLS -> calls(query, cancel)
        Category.TEXT_MESSAGES -> messages(query, cancel)
        Category.CALENDAR -> calendar(query, cancel)
        Category.PICTURES, Category.VIDEOS, Category.MUSIC, Category.DOCUMENTS -> media(category, query, cancel)
        else -> NONE
    }

    /** The provider's own filter: names, nicknames, addresses, numbers. */
    private fun contacts(query: String, cancel: CancellationSignal): Found {
        if (!granted(Manifest.permission.READ_CONTACTS)) return NONE
        val uri = Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        val projection = arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_THUMBNAIL_URI)
        return resolver.query(uri, projection, null, null, Contacts.SORT_KEY_PRIMARY, cancel)?.use { c ->
            found(c.rows { Contact(it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty(), it.getString(3)) })
        } ?: NONE
    }

    private fun calls(query: String, cancel: CancellationSignal): Found {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return NONE
        val word = narrowing(query) ?: return NONE
        val projection = arrayOf(Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE)
        val where = "${Calls.NUMBER} LIKE ? ESCAPE '\\' OR ${Calls.CACHED_NAME} LIKE ? ESCAPE '\\'"
        return resolver.query(Calls.CONTENT_URI, projection, where, arrayOf(like(word), like(word)), "${Calls.DATE} DESC", cancel)?.use { c ->
            found(
                c.rows {
                    val number = it.getString(0).orEmpty()
                    val name = it.getString(1)
                    Call(number, name, it.getInt(2), it.getLong(3)).takeIf { name?.let { n -> matchRanges(n, query) } != null || numberMatches(number, query) }
                },
            )
        } ?: NONE
    }

    /** SMS and MMS whose text has the query's words, or whose number its digits; latest first. */
    private fun messages(query: String, cancel: CancellationSignal): Found {
        if (!granted(Manifest.permission.READ_SMS)) return NONE
        val word = narrowing(query) ?: return NONE
        fun keep(address: String, body: String) = matchRanges(body, query) != null || numberMatches(address, query)
        val sms = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\' OR ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'",
            arrayOf(like(word), like(word)),
            "${Telephony.Sms.DATE} DESC",
            cancel,
        )?.use { c -> c.rows { r -> Pair(r.getString(0).orEmpty(), r.getString(1).orEmpty()).takeIf { keep(it.first, it.second) }?.let { Triple(it.first, it.second, r.getLong(2)) } } }.orEmpty()
        // An MMS keeps its text in a part, its date (in seconds) in the message, its number in an address.
        val mms = resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("mid", "text"),
            "ct = 'text/plain' AND text LIKE ? ESCAPE '\\'",
            arrayOf(like(word)),
            "_id DESC",
            cancel,
        )?.use { c -> c.rows { r -> Pair(r.getLong(0), r.getString(1).orEmpty()) } }.orEmpty()
            .take(MAX_RESULTS)
            .mapNotNull { (mid, text) -> mmsAddress(mid, cancel)?.takeIf { keep(it, text) }?.let { Triple(mid, it, text) } }
        // Their dates in one query.
        val dates = if (mms.isEmpty()) emptyMap() else resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
            "${Telephony.Mms._ID} IN (${mms.joinToString(",") { "?" }})",
            mms.map { it.first.toString() }.toTypedArray(),
            null,
            cancel,
        )?.use { c -> c.rows { it.getLong(0) to it.getLong(1) * 1000 }.toMap() }.orEmpty()
        val withDates = mms.map { (mid, address, text) -> Triple(address, text, dates[mid] ?: 0L) }
        val all = (sms + withDates).sortedByDescending { it.third }
        return Found(all.take(MAX_RESULTS).map { (address, body, date) -> Message(address, name(address, cancel), body, date) }, all.size)
    }

    private fun mmsAddress(mid: Long, cancel: CancellationSignal): String? =
        // 137: from, 151: to (PduHeaders); a sent MMS is from "insert-address-token".
        resolver.query(Uri.parse("content://mms/$mid/addr"), arrayOf("address"), "type = 137 OR type = 151", null, "type ASC", cancel)?.use { c ->
            c.rows { it.getString(0)?.takeIf { a -> a != "insert-address-token" } }.firstOrNull()
        }

    private fun name(number: String, cancel: CancellationSignal): String? {
        if (!granted(Manifest.permission.READ_CONTACTS) || number.isBlank()) return null
        return names.getOrPut(number) {
            resolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null, cancel)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }

    /** The provider's search over 90 days back and a year on (title, place, description). */
    private fun calendar(query: String, cancel: CancellationSignal): Found {
        if (!granted(Manifest.permission.READ_CALENDAR)) return NONE
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val uri = Instances.CONTENT_SEARCH_URI.buildUpon().also {
            ContentUris.appendId(it, now - 90 * day)
            ContentUris.appendId(it, now + 365 * day)
        }.appendPath(query.trim()).build()
        val projection = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.EVENT_LOCATION, Instances.ALL_DAY)
        val events = resolver.query(uri, projection, null, null, null, cancel)?.use { c ->
            c.rows { Event(it.getLong(0), it.getString(1).orEmpty(), it.getLong(2), it.getString(3).orEmpty(), it.getInt(4) != 0) }
        } ?: return NONE
        return found(upcomingFirst(events.filter { matchRanges(it.title, query) != null || matchRanges(it.location, query) != null }, now, Event::begin, Event::id))
    }

    /**
     * Pictures, videos, songs (also by artist and album) and documents, all with the all files
     * access the priv-app has (MANAGE_EXTERNAL_STORAGE, preinstalled); `appops set
     * dev.bold7.launcher MANAGE_EXTERNAL_STORAGE ignore` turns the four categories off.
     */
    private fun media(category: Category, query: String, cancel: CancellationSignal): Found {
        if (!Environment.isExternalStorageManager()) return NONE
        val word = narrowing(query) ?: return NONE
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        val uri = when (category) {
            Category.PICTURES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Category.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Category.MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        val l = like(word)
        val date = MediaStore.MediaColumns.DATE_MODIFIED
        if (category == Category.MUSIC) {
            val projection = arrayOf(MediaStore.MediaColumns._ID, A.TITLE, A.ARTIST, A.ALBUM, MediaStore.MediaColumns.MIME_TYPE, date)
            val where = "${A.TITLE} LIKE ? ESCAPE '\\' OR ${A.ARTIST} LIKE ? ESCAPE '\\' OR ${A.ALBUM} LIKE ? ESCAPE '\\'"
            return resolver.query(uri, projection, where, arrayOf(l, l, l), MUSIC_ORDER, cancel)?.use { c ->
                found(
                    c.rows {
                        val title = it.getString(1).orEmpty()
                        val detail = listOfNotNull(it.getString(2), it.getString(3)).filter(String::isNotBlank).joinToString(", ")
                        Media(ContentUris.withAppendedId(uri, it.getLong(0)), title, detail, it.getString(4), it.getLong(5) * 1000)
                            .takeIf { matchRanges(title, query) != null || matchRanges(detail, query) != null }
                    },
                )
            } ?: NONE
        }
        // Documents: no picture, video or song, and not a folder (no MIME type).
        val where = if (category == Category.DOCUMENTS) {
            "${F.MEDIA_TYPE} IN (${F.MEDIA_TYPE_NONE}, ${F.MEDIA_TYPE_DOCUMENT}) AND ${F.MIME_TYPE} IS NOT NULL AND $name LIKE ? ESCAPE '\\'"
        } else {
            "$name LIKE ? ESCAPE '\\'"
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID, name, MediaStore.MediaColumns.MIME_TYPE, date)
        return resolver.query(uri, projection, where, arrayOf(l), "$date DESC", cancel)?.use { c ->
            found(
                c.rows {
                    val title = it.getString(1).orEmpty()
                    Media(ContentUris.withAppendedId(uri, it.getLong(0)), title, "", it.getString(2), it.getLong(3) * 1000).takeIf { matchRanges(title, query) != null }
                },
            )
        } ?: NONE
    }

    /** [contact]'s picture, null without one (or gone in between). */
    fun photo(contact: Contact): Bitmap? = contact.photo?.let { uri ->
        if (isAvatar(uri)) return avatar(context, uri, 96)
        try {
            resolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** The system's thumbnail of a picture or a video, [size] px square. */
    fun thumbnail(media: Media, size: Int): Bitmap? = try {
        resolver.loadThumbnail(media.uri, Size(size, size), null)
    } catch (e: java.io.IOException) {
        null
    }
}
