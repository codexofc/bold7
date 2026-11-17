package dev.bold7.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import android.provider.CalendarContract.Events
import android.provider.CallLog.Calls
import android.provider.ContactsContract.PhoneLookup
import android.provider.Telephony
import dev.bold7.hub.data.CallRow
import dev.bold7.hub.data.EventRow
import dev.bold7.hub.data.MmsRow
import dev.bold7.hub.data.ProviderRows
import dev.bold7.hub.data.SmsRow

/**
 * Android's providers, read whole on the sources' thread only (HubGraph), with the permissions
 * tools/install.sh grants. Nothing read is logged.
 */
class AndroidRows(private val context: Context) : ProviderRows {
    private val resolver = context.contentResolver
    // Asked at each lookup until granted (tools/install.sh may grant it after this process started);
    // PermissionManager answers from its cache.
    private var contacts = false
        get() = field || (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED).also { field = it }

    // Kept until a contact changes (HubGraph observes the contacts and calls forgetNames): a contact
    // added or renamed names its texts at the next read. Unknown numbers are kept too.
    private val names = HashMap<String, String?>()
    @Volatile private var namesStale = false

    /** A contact was added, renamed or deleted: the names are looked up again at the next read of the texts. */
    fun forgetNames() {
        namesStale = true
    }

    // An MMS's address never changes.
    private val mmsAddresses = HashMap<Long, String?>()

    // No cursor: the provider's process died during the query. A failure (HubGraph counts it),
    // never an empty read, after which HubSources would forget the account. The URI is not in
    // the message: a PhoneLookup's carries a number.
    private fun <T> rows(uri: Uri, projection: Array<String>, selection: String? = null, row: (Cursor) -> T): List<T> =
        checkNotNull(resolver.query(uri, projection, selection, null, null)) { "A provider answered no cursor" }
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(row(c))
                    // A provider dying past the cursor's first window ends the loop early, silently.
                    check(size == c.count) { "A provider's cursor ended early" }
                }
            }

    // getOrPut would not keep a null: unknown numbers would be looked up at every read.
    private fun <K, V> HashMap<K, V?>.cached(key: K, compute: () -> V?): V? =
        if (containsKey(key)) get(key) else compute().also { put(key, it) }

    private fun name(address: String?): String? {
        if (!contacts || address.isNullOrBlank()) return null
        return names.cached(address) {
            rows(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)), arrayOf(PhoneLookup.DISPLAY_NAME)) { it.getString(0) }.firstOrNull()
        }
    }

    override fun sms(): List<SmsRow> {
        // On the sources' thread, as every read of names: cleared here, never from the observer's binder thread.
        if (namesStale) {
            namesStale = false
            names.clear()
        }
        val s = Telephony.Sms.CONTENT_URI
        return rows(s, arrayOf(BaseColumns._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.READ, Telephony.Sms.TYPE)) { c ->
            val address = c.getString(2)
            SmsRow(c.getLong(0), c.getLong(1), address, name(address), c.getLong(3), c.getString(4).orEmpty(), c.getInt(5) != 0, c.getInt(6))
        }
    }

    override fun mms(): List<MmsRow> {
        // All parts in one query: text joined, the others (but the SMIL layout) attachments.
        val parts = rows(Uri.parse("content://mms/part"), arrayOf("mid", "ct", "text")) { Triple(it.getLong(0), it.getString(1), it.getString(2)) }
            .groupBy { it.first }
        val m = Telephony.Mms.CONTENT_URI
        // PduHeaders: send_req, notification_ind, retrieve_conf, as AOSP's Mms app and Telephony's
        // counters select. Not the delivery and read reports (134, 136), kept in the inbox too.
        val messages = "${Telephony.Mms.MESSAGE_TYPE} IN (128, 130, 132)"
        val members = threadMembers()
        return rows(m, arrayOf(BaseColumns._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.SUBJECT, Telephony.Mms.READ, Telephony.Mms.MESSAGE_BOX), messages) { c ->
            val id = c.getLong(0)
            val box = c.getInt(5)
            val own = parts[id].orEmpty()
            val address = mmsAddresses.cached(id) { mmsAddress(id, box) }
            MmsRow(
                id, c.getLong(1), address, name(address), c.getLong(2), c.getString(3),
                text = own.filter { it.second == "text/plain" }.mapNotNull { it.third }.joinToString("\n"),
                read = c.getInt(4) != 0,
                box = box,
                attachments = own.count { it.second != "text/plain" && it.second != "application/smil" },
                recipients = members[c.getLong(1)].orEmpty(),
            )
        }
    }

    /**
     * Each thread's members, the user left out: Telephony's threads (recipient_ids, ids of
     * canonical addresses separated by spaces), as AOSP's Mms app reads them. Read again at each
     * sync: a group can grow. A group MMS names them all (MmsRow.recipients).
     */
    private fun threadMembers(): Map<Long, List<String>> {
        val addresses = rows(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf(BaseColumns._ID, "address")) { it.getLong(0) to it.getString(1) }
            .toMap()
        return rows(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf(BaseColumns._ID, "recipient_ids")) { c ->
            c.getLong(0) to c.getString(1).orEmpty().split(' ').mapNotNull { it.toLongOrNull()?.let(addresses::get) }
        }.toMap()
    }

    // PduHeaders: 137 from, for a received MMS; 151 to, for the others. A sent MMS is from
    // "insert-address-token".
    private fun mmsAddress(id: Long, box: Int): String? =
        rows(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type = ${if (box == Telephony.Mms.MESSAGE_BOX_INBOX) 137 else 151}") { it.getString(0) }
            .firstOrNull { it != null && it != "insert-address-token" }

    // The whole log (Android keeps 500 calls): HubSources keeps the missed ones.
    override fun calls() = rows(Calls.CONTENT_URI, arrayOf(Calls._ID, Calls.NUMBER, Calls.CACHED_NAME, Calls.DATE, Calls.TYPE, Calls.NEW, Calls.IS_READ)) { c ->
        CallRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4), c.getInt(5) != 0, c.getInt(6) != 0)
    }

    // Simplification: every event not deleted, HubSources keeps the invitations; a selection in SQL if a
    // synced calendar makes this slow.
    override fun events() = rows(
        Events.CONTENT_URI,
        arrayOf(Events._ID, Events.TITLE, Events.ORGANIZER, Events.DTSTART, Events.SELF_ATTENDEE_STATUS, Events.IS_ORGANIZER, Events.HAS_ATTENDEE_DATA),
        "${Events.DELETED} = 0",
    ) { c -> EventRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4), c.getInt(5) != 0, c.getInt(6) != 0) }
}
