package dev.bold7.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import android.util.Log
import java.io.IOException

/**
 * Android's contacts for To and the band, read only: READ_CONTACTS, granted by
 * tools/install.sh; Select Contact's row also through the read grant of its answer. Blocking: on
 * MessagesGraph's thread only. Refused or failing, it reads as nothing; nothing read is logged.
 */
class AndroidContactBook(context: Context) : ContactBook {
    private val resolver = context.contentResolver

    override fun all(): List<Contact> = read(emptyList()) {
        val numbers = LinkedHashMap<Long, Pair<String, MutableList<PhoneNumber>>>()
        resolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE),
            null, null, "${Phone.SORT_KEY_PRIMARY}, ${Phone.IS_SUPER_PRIMARY} DESC, ${Phone._ID}",
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                numbers.getOrPut(c.getLong(0)) { c.getString(1).orEmpty() to ArrayList() }.second += PhoneNumber(phoneLabel(c.getInt(3)), number)
            }
        }
        numbers.values.map { (name, list) -> Contact(name, list) }
    }

    override fun photo(number: String): String? = read(null) {
        resolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    override fun name(number: String): String? = read(null) {
        resolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }

    override fun chosen(uri: String): Recipient? = read(null) {
        resolver.query(Uri.parse(uri), arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME_PRIMARY, Phone.TYPE, Phone.MIMETYPE), null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getString(3) != Phone.CONTENT_ITEM_TYPE) return@use null
            val number = c.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            Recipient(number, c.getString(1)?.takeIf { it.isNotBlank() }, phoneLabel(c.getInt(2)))
        }
    }

    /** The contact's picture at [uri] (a thumbnail, 96 px at most), for the band; null when unreadable. */
    fun picture(uri: String): Bitmap? = try {
        resolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
    } catch (e: IOException) {
        Log.w(TAG, "picture unreadable: ${e.javaClass.name}")
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "picture unreadable: ${e.javaClass.name}")
        null
    }

    private inline fun <T> read(none: T, block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        // SecurityException without READ_CONTACTS, a provider gone: never a name or a number logged.
        Log.w(TAG, "contacts unreadable: ${e.javaClass.name}")
        none
    }
}
