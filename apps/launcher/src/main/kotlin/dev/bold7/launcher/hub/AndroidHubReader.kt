package dev.bold7.launcher.hub

import android.app.PendingIntent
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.text.format.DateFormat
import android.util.Log
import dev.bold7.design.OS7_NAMES
import dev.bold7.hub.data.Account
import dev.bold7.hub.data.AccountColor
import dev.bold7.hub.data.AccountKind
import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.itemFromRow
import dev.bold7.hub.data.keyArrays
import java.time.ZoneId

private const val TAG = "Bold7/Hub"

/**
 * Settings.Global key: when above 0, the list of notifications shows that many made-up items instead
 * of the Hub's, for the scrolling measure (tools/perf.sh). Set and removed by adb only
 * (`settings put global` / `settings delete global`); the user's messages are never touched.
 */
const val HUB_DEMO_SETTING = "bold7_hub_demo"

/** At most this many made-up items, whatever the setting says. */
const val HUB_DEMO_MAX = 500

/**
 * The Hub's provider in apps/hub's process (HubContract), and Android's settings around it.
 * Blocking binder calls: on the Hub's own thread only (LauncherGraph).
 */
class AndroidHubReader(private val context: Context) : HubReader {
    private val resolver = context.contentResolver

    override fun read(): HubSnapshot? {
        val demo = Settings.Global.getInt(resolver, HUB_DEMO_SETTING, 0)
        if (demo > 0) return demoSnapshot(demo, System.currentTimeMillis())
        // Another process's data, of another APK's version: anything it throws (refused, SQLite,
        // a column or a value this launcher does not know) is a failed read, logged by its class
        // only (a message could hold a number), and the page keeps what it showed.
        // Two queries, between two writes of the Hub at worst: the next change reads both again.
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
        return HubSnapshot(
            items = rows.map { it.first },
            smsApp = Telephony.Sms.getDefaultSmsPackage(context),
            dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage,
            accounts = accounts,
        )
    }

    /**
     * The accounts in their order, from the counters' cursor (HubContract.COUNTS_URI): its first
     * row is the whole Hub's, without an account. The counts the page shows are worked out from
     * the items, as the list shows them (accountLines).
     */
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

    private fun items(c: Cursor): List<Pair<HubItem, AccountColor>> {
        val columns = HubContract.ITEM_COLUMNS.map(c::getColumnIndexOrThrow)
        return buildList(c.count) {
            while (c.moveToNext()) add(itemFromRow { i -> value(c, columns[i]) })
        }
    }

    private fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        else -> c.getString(i)
    }

    override fun format() = HubFormat(ZoneId.systemDefault(), OS7_NAMES, DateFormat.is24HourFormat(context))

    // The made-up items of the measure are written nowhere: the Hub's database is never touched.
    override fun write(method: String, keys: List<ItemKey>) {
        if (Settings.Global.getInt(resolver, HUB_DEMO_SETTING, 0) > 0) return
        val (accounts, sourceKeys) = keyArrays(keys)
        val extras = Bundle().apply {
            putStringArray(HubContract.EXTRA_ACCOUNTS, accounts)
            putStringArray(HubContract.EXTRA_KEYS, sourceKeys)
        }
        resolver.call(Uri.parse("content://${HubContract.AUTHORITY}"), method, null, extras)
    }

    /**
     * The live contentIntent of a notification, the notification closed if it closes on a tap
     * (HubContract.METHOD_OPEN); null when gone, or when the Hub cannot say.
     */
    /** [packageName]'s label for Android, null if it is gone. Blocking: the Hub's thread only. */
    fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }

    fun open(key: String): PendingIntent? = try {
        resolver.call(Uri.parse("content://${HubContract.AUTHORITY}"), HubContract.METHOD_OPEN, key, null)
            ?.getParcelable(HubContract.EXTRA_INTENT, PendingIntent::class.java)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Hub open refused: ${e.javaClass.name}")
        null
    }
}

/**
 * [n] made-up items, at most [HUB_DEMO_MAX], a mix of kinds over a week, half of them unread and new: as
 * many rows as items, with a day header every thirty or so. Each sender starts with "Demo", so a
 * setting left on shows for what it is. Nothing of them reaches the Hub's database.
 */
fun demoSnapshot(count: Int, now: Long): HubSnapshot {
    val n = count.coerceIn(0, HUB_DEMO_MAX)
    val names = listOf("Kelsi Smith", "Andy Marken", "The Washington Post", "MileagePlus Program", "Jonah", "Crossy Road")
    val texts = listOf(
        "Eco & Innovation at CES with Looptworks",
        "All 4K Channel Plays Everywhere at CES Especially At Home",
        "See you at 6 pm?",
        "Earn 50% more award miles when you exchange gift cards",
        "OK",
    )
    val kinds = listOf(ItemKind.SMS, ItemKind.NOTIFICATION, ItemKind.MISSED_CALL, ItemKind.SMS, ItemKind.NOTIFICATION, ItemKind.INVITATION)
    val items = List(n) { i ->
        val kind = kinds[i % kinds.size]
        HubItem(
            accountId = "demo-${kind.name.lowercase()}", sourceKey = "demo:$i", kind = kind,
            timestamp = now - i * 47 * 60_000L, sender = "Demo ${i + 1}, ${names[i % names.size]}",
            address = if (kind == ItemKind.MISSED_CALL) "+33 6 00 00 00 ${(i % 90 + 10)}" else null,
            subject = if (kind == ItemKind.INVITATION) texts[i % texts.size] else null,
            preview = texts[i % texts.size], box = Box.INBOX, read = i % 2 == 1,
            // Never seen (seenAt 0): the unread ones are new, and the splats show (tools/perf.sh, HUB_SPLATS).
            addedAt = now - i * 47 * 60_000L,
        )
    }
    val accounts = listOf(
        ItemKind.NOTIFICATION to AccountKind.NOTIFICATIONS,
        ItemKind.SMS to AccountKind.TEXT_MESSAGES,
        ItemKind.MISSED_CALL to AccountKind.CALLS,
        ItemKind.INVITATION to AccountKind.CALENDAR,
    ).mapIndexed { i, (item, account) -> Account("demo-${item.name.lowercase()}", account, name = "", position = i) }
    return HubSnapshot(items, smsApp = null, dialer = null, accounts = accounts, demo = true)
}
