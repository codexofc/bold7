package dev.bold7.messages

import android.app.PendingIntent
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telecom.TelecomManager
import android.text.format.DateFormat
import android.util.Log
import dev.bold7.design.OS7_NAMES
import dev.bold7.hub.data.Account
import dev.bold7.hub.data.AccountKind
import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.itemFromRow
import dev.bold7.hub.data.keyArrays
import java.time.ZoneId

internal const val TAG = "Bold7/Messages"

/** At most this many made-up items (`--ei rows N`, tools/perf.sh), whatever is asked. */
const val DEMO_MAX = 500

/**
 * The Hub's provider in apps/hub's process (HubContract), and Android's settings around it,
 * taken from the launcher's hub/AndroidHubReader.kt. Blocking binder calls: on the feed's thread
 * only (MessagesGraph).
 */
class AndroidInboxReader(private val context: Context, private val drafts: Drafts, private val store: MessagesStore) : InboxReader {
    private val resolver = context.contentResolver
    private val hub = Uri.parse("content://${HubContract.AUTHORITY}")

    /** Above 0, the list shows that many made-up items instead of the Hub's (the scrolling measure). */
    @Volatile var demo = 0

    override fun read(): Snapshot? {
        if (demo > 0) return demoSnapshot(demo, System.currentTimeMillis()).copy(options = store.options)
        // Another process's data, of another APK's version: anything it throws (refused, SQLite, a
        // column or a value this app does not know) is a failed read, logged by its class only (a
        // message could hold a number), and the list keeps what it showed.
        val (rows, accounts) = try {
            val rows = resolver.query(Uri.parse(HubContract.ITEMS_URI), null, null, null, null)?.use(::items)
            rows to resolver.query(Uri.parse(HubContract.COUNTS_URI), null, null, null, null)?.use(::accounts)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Hub items unreadable: ${e.javaClass.name}")
            return null
        }
        if (rows == null || accounts == null) {
            Log.w(TAG, "Hub items: no provider") // apps/hub missing, or before the first unlock
            return null
        }
        return Snapshot(
            // The mail drafts kept here, with the Hub's items.
            items = rows + drafts.all().map { it.asItem() },
            accounts = accounts,
            smsApp = Telephony.Sms.getDefaultSmsPackage(context),
            dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage,
            options = store.options,
            saved = store.saved,
        )
    }

    /** The accounts in their order and their last visit, from the counters' cursor; its first row is the whole Hub's. */
    private fun accounts(c: Cursor): List<Account> {
        val id = c.getColumnIndexOrThrow(HubContract.ACCOUNT)
        val kind = c.getColumnIndexOrThrow(HubContract.KIND)
        val seen = c.getColumnIndexOrThrow(HubContract.SEEN)
        return buildList {
            while (c.moveToNext()) {
                if (c.isNull(id)) continue
                add(Account(c.getString(id), AccountKind.valueOf(c.getString(kind)), name = "", position = size, seenAt = c.getLong(seen)))
            }
        }
    }

    private fun items(c: Cursor): List<HubItem> {
        val columns = HubContract.ITEM_COLUMNS.map(c::getColumnIndexOrThrow)
        return buildList(c.count) {
            while (c.moveToNext()) add(itemFromRow { i -> value(c, columns[i]) }.first)
        }
    }

    private fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        else -> c.getString(i)
    }

    override fun format() = inboxFormat(context)

    override fun write(method: String, keys: List<ItemKey>) {
        val (accounts, sourceKeys) = keyArrays(keys)
        val extras = Bundle().apply {
            putStringArray(HubContract.EXTRA_ACCOUNTS, accounts)
            putStringArray(HubContract.EXTRA_KEYS, sourceKeys)
        }
        resolver.call(hub, method, null, extras)
    }

    override fun markSeen(upTo: Map<String, Long>) {
        val entries = upTo.entries.toList()
        val extras = Bundle().apply {
            putStringArray(HubContract.EXTRA_ACCOUNTS, Array(entries.size) { entries[it].key })
            putLongArray(HubContract.EXTRA_SEEN_UP_TO, LongArray(entries.size) { entries[it].value })
        }
        resolver.call(hub, HubContract.METHOD_MARK_SEEN, null, extras)
    }

    /** The live contentIntent of a notification (HubContract.METHOD_OPEN); null when gone, or when the Hub cannot say. */
    fun open(key: String): PendingIntent? = try {
        resolver.call(hub, HubContract.METHOD_OPEN, key, null)?.getParcelable(HubContract.EXTRA_INTENT, PendingIntent::class.java)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Hub open refused: ${e.javaClass.name}")
        null
    }
}

/** The phone's zone, language and clock. */
fun inboxFormat(context: Context) = InboxFormat(ZoneId.systemDefault(), OS7_NAMES, DateFormat.is24HourFormat(context))

/**
 * [count] made-up items, at most [DEMO_MAX], a mix of kinds over a fortnight, half unread, for the
 * scrolling measure (tools/perf.sh, APP mode). Each sender starts with "Demo"; nothing of them
 * reaches the Hub's database (Feed: never written, never seen).
 */
fun demoSnapshot(count: Int, now: Long): Snapshot {
    val n = count.coerceIn(0, DEMO_MAX)
    val names = listOf("Kelly Nielsen", "Emma Kinsella", "Bern Paterson", "Albert Johnson", "Alice Martin", "Paul Durand")
    val texts = listOf("Test email", "Security Activation Required", "See you at 6 pm?", "Registration", "OK")
    val kinds = listOf(ItemKind.SMS, ItemKind.NOTIFICATION, ItemKind.MISSED_CALL, ItemKind.SMS, ItemKind.MMS)
    val items = List(n) { i ->
        val kind = kinds[i % kinds.size]
        HubItem(
            accountId = "demo", sourceKey = "demo:$i", kind = kind, timestamp = now - i * 47 * 60_000L,
            sender = "Demo ${i + 1}, ${names[i % names.size]}",
            address = if (kind == ItemKind.MISSED_CALL) "+33 1 99 00 00 ${i % 90 + 10}" else null,
            preview = texts[i % texts.size], box = if (i % 7 == 3) Box.SENT else Box.INBOX, read = i % 2 == 1,
            attachmentCount = if (kind == ItemKind.MMS) 1 else 0,
        )
    }
    return Snapshot(items, listOf(Account("demo", AccountKind.TEXT_MESSAGES, "")), smsApp = null, dialer = null, demo = true)
}
