package dev.bold7.messages

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log
import java.io.IOException

/**
 * One thread of Telephony (READ_SMS, granted by tools/install.sh): SMS, MMS and the RCS Google
 * Messages copies there, as AOSP's Mms app reads them. Blocking: on MessagesGraph's thread only.
 * Nothing read is logged.
 */
class AndroidThreadReader(context: Context) : ThreadReader {
    private val resolver = context.contentResolver

    override fun read(threadId: Long): List<ThreadMessage>? = try {
        threadMessages(sms(threadId), mms(threadId))
    } catch (e: RuntimeException) {
        // Refused (READ_SMS), provider gone: the thread keeps what it shows.
        Log.w(TAG, "thread unreadable: ${e.javaClass.name}")
        null
    }

    /**
     * Telephony's threads, newest first, each with its members (recipient_ids, ids of canonical
     * addresses separated by spaces), as the Hub reads them (AndroidRows.threadMembers).
     */
    override fun threads(): List<Pair<Long, List<String>>>? = try {
        val addresses = rows(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf(BaseColumns._ID, "address"), null, null) { it.getLong(0) to it.getString(1) }.toMap()
        rows(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf(BaseColumns._ID, "recipient_ids"), null, null, "date DESC") { c ->
            c.getLong(0) to c.getString(1).orEmpty().split(' ').mapNotNull { it.toLongOrNull()?.let(addresses::get) }
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "threads unreadable: ${e.javaClass.name}")
        null
    }

    private fun <T> rows(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String? = null, row: (Cursor) -> T): List<T> =
        checkNotNull(resolver.query(uri, projection, selection, args, order)) { "A provider answered no cursor" }.use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }

    private fun sms(thread: Long) = rows(
        Telephony.Sms.CONTENT_URI,
        arrayOf(BaseColumns._ID, Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.STATUS, Telephony.Sms.READ, Telephony.Sms.ADDRESS),
        "${Telephony.Sms.THREAD_ID} = ?", arrayOf("$thread"),
    ) { c -> SmsRecord(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getInt(5) != 0, c.getString(6)) }

    private fun mms(thread: Long): List<MmsRecord> {
        // PduHeaders: send_req, notification_ind, retrieve_conf, as the Hub reads them (not the reports).
        val heads = rows(
            Telephony.Mms.CONTENT_URI,
            arrayOf(BaseColumns._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_TYPE} IN (128, 130, 132)", arrayOf("$thread"),
        ) { c -> listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) }
        if (heads.isEmpty()) return emptyList()
        val parts = rows(
            Uri.parse("content://mms/part"), arrayOf("mid", BaseColumns._ID, "ct", "text"),
            "mid IN (${heads.joinToString { "${it[0]}" }})", null,
        ) { c -> Part(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getString(3)) }.groupBy { it.mid }
        return heads.map { (id, seconds, box, read) ->
            val own = parts[id].orEmpty()
            MmsRecord(
                id, seconds, box.toInt(), read != 0L,
                text = own.filter { it.type == "text/plain" }.mapNotNull { it.text }.joinToString("\n"),
                images = own.filter { it.type.startsWith("image/") }.map { it.id },
                others = own.filter { it.type != "text/plain" && it.type != "application/smil" && !it.type.startsWith("image/") }.map { it.id to it.type },
                address = if (box == Telephony.Mms.MESSAGE_BOX_INBOX.toLong()) from(id) else null,
            )
        }
    }

    private data class Part(val mid: Long, val id: Long, val type: String, val text: String?)

    // PduHeaders 137: from. A sent MMS is from "insert-address-token".
    private fun from(id: Long): String? =
        rows(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type = 137", null) { it.getString(0) }.firstOrNull { it != null && it != "insert-address-token" }

    /**
     * An MMS image ([uri], content://mms/part/<id>) decoded at most [maxWidth] wide, a power of two
     * smaller (never enlarged); null when unreadable.
     */
    fun image(uri: String, maxWidth: Int): Bitmap? = try {
        val part = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(part)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        resolver.openInputStream(part)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (e: IOException) {
        Log.w(TAG, "image unreadable: ${e.javaClass.name}")
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "image unreadable: ${e.javaClass.name}")
        null
    }
}
