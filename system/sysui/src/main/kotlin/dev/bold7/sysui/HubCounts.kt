package dev.bold7.sysui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.bold7.showcase.SHOWCASE_EVENT_SETTING
import dev.bold7.showcase.SHOWCASE_SETTING
import dev.bold7.showcase.pick
import dev.bold7.showcase.showcaseEvent
import dev.bold7.showcase.showcaseOn
import java.util.WeakHashMap

// The Hub's contract (core/hub-data, HubContract), restated: this module does not bundle the Hub's
// library (Room) into SystemUI's process.
private val HUB_COUNTS = Uri.parse("content://dev.bold7.hub.data/counts")
private val HUB_KINDS = Uri.parse("content://dev.bold7.hub.data/kinds")
private const val KIND = "kind"
private const val UNREAD = "unread"

/** Android's "show notifications on the lock screen" (Settings.Secure, hidden name): 0 hides them all. */
private const val LOCK_SCREEN_SHOW_NOTIFICATIONS = "lock_screen_show_notifications"

/**
 * The counters of every banner SystemUI draws, an app's and the lock screen's, read from the Hub
 * (HubContract.KINDS_URI): the numbers the home's banner shows, from the same count, so that the
 * three never differ. SystemUI reads that path with STATUS_BAR (apps/hub's manifest). Read again at
 * each write of the Hub (it notifies its counts' URI), at the first unlock (nothing is readable
 * before: the Hub is not direct boot aware), and when Android's lock screen setting changes. The
 * query runs on its own thread, never on the main one; banners are told on the main thread.
 */
internal object HubCounts {
    /** The banners shown, weakly (SystemUI inflates its bars again), with whether each is the lock screen's. Main thread. */
    private val banners = WeakHashMap<BannerView, Boolean>()
    private var counters = emptyList<Counter>()
    private var lockShown = true
    private var started = false
    private var refused = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io by lazy { Handler(HandlerThread("Bold7HubCounts").apply { start() }.looper) }

    /** [banner] shows the counters from now on, at once; [lock] the lock screen's, which follow Android's setting. Main thread. */
    fun attach(banner: BannerView, lock: Boolean) {
        banners[banner] = lock
        banner.setCounters(shown(lock))
        if (started) return
        started = true
        val context = banner.context.applicationContext
        io.post { start(context) }
    }

    fun detach(banner: BannerView) {
        banners.remove(banner)
    }

    private fun shown(lock: Boolean) = if (lock && !lockShown) emptyList() else counters

    // On io (the registrations are binder calls), its own thread: nothing may escape into SystemUI.
    private fun start(context: Context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(io) {
            override fun onChange(selfChange: Boolean) = read(context)
        }
        try {
            resolver.registerContentObserver(HUB_COUNTS, false, observer)
        } catch (e: SecurityException) {
            // The Hub is not installed (Android refuses to observe an unknown provider): no counters.
            Log.w(TAG, "counters: the Hub cannot be observed, none shown", e)
        }
        try {
            resolver.registerContentObserver(Settings.Secure.getUriFor(LOCK_SCREEN_SHOW_NOTIFICATIONS), false, observer)
            resolver.registerContentObserver(Settings.Global.getUriFor(SHOWCASE_SETTING), false, observer)
            resolver.registerContentObserver(Settings.Global.getUriFor(SHOWCASE_EVENT_SETTING), false, observer)
            // Protected: reaches a receiver that is not exported.
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = read(context)
            }, IntentFilter(Intent.ACTION_USER_UNLOCKED), null, io, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: RuntimeException) {
            Log.w(TAG, "counters: not told of the first unlock nor of the lock screen's setting", e)
        }
        read(context)
    }

    // On io. Locked before the first unlock, no Hub, a Hub without this path, an SQLite error: no
    // counters, said once in the log.
    private fun read(context: Context) {
        val show = try {
            Settings.Secure.getInt(context.contentResolver, LOCK_SCREEN_SHOW_NOTIFICATIONS, 1) != 0
        } catch (e: RuntimeException) {
            Log.w(TAG, "counters: the lock screen's setting is unreadable, none there", e)
            false
        }
        val rows = try {
            unreadRows(showcaseOn(context), showcaseEvent(context)) {
                context.contentResolver.query(HUB_KINDS, null, null, null, null)?.use { c ->
                    val kind = c.getColumnIndexOrThrow(KIND)
                    val unread = c.getColumnIndexOrThrow(UNREAD)
                    buildMap { while (c.moveToNext()) put(c.getString(kind), c.getInt(unread)) }
                }.orEmpty()
            }
        } catch (e: RuntimeException) {
            if (!refused) Log.w(TAG, "counters: the Hub is unreadable, none shown", e)
            refused = true
            emptyMap()
        }
        val next = kindCounters(rows)
        main.post {
            if (next == counters && show == lockShown) return@post
            counters = next
            lockShown = show
            for ((banner, lock) in banners) banner.setCounters(shown(lock))
        }
    }
}
