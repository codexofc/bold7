package dev.bold7.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import dev.bold7.hub.data.HubSources
import dev.bold7.hub.data.hubStore
import java.io.PrintWriter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

internal const val TAG = "Bold7/Hub"

/**
 * The Hub process's objects, built by hand (docs/ARCHITECTURE.md §5). Every source runs on one thread, one task at a time:
 * the main thread only registers the observers. No polling: the providers' change notifications
 * and the notification listener's callbacks are the only triggers.
 */
class HubGraph(private val app: Context) {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "hub-sources") }
    private val rows = AndroidRows(app)
    private val sources = HubSources(hubStore(app), rows, System::currentTimeMillis)
    val diagnostics = Diagnostics()

    /** Mail: its accounts, their sync and push, Messages' calls. */
    val mail = MailHost(app, hubStore(app))

    /** Sources whose permission was missing, started by [retryRefused] once it is granted. */
    private val refused = ConcurrentHashMap<String, Pair<String, () -> Unit>>()

    fun start() {
        watch("texts", Manifest.permission.READ_SMS, listOf(Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI)) { syncTexts() }
            ?.let(::watchContacts)
        watch("calls", Manifest.permission.READ_CALL_LOG, listOf(CallLog.Calls.CONTENT_URI)) { syncCalls() }
        watch("invitations", Manifest.permission.READ_CALENDAR, listOf(CalendarContract.CONTENT_URI)) { syncInvitations() }
        mail.start()
    }

    /**
     * Starts the sources refused at [start] whose permission came since (tools/install.sh grants
     * them after the first install, when a reader may already have started this process). An
     * ordinary app hears of no grant (OnPermissionsChangedListener needs
     * OBSERVE_GRANT_REVOKE_PERMISSIONS, signature|privileged): the provider's readers trigger it,
     * the launcher each time its home shows. Nothing to do once every source runs.
     */
    fun retryRefused() {
        for ((source, waiting) in refused) {
            val (permission, start) = waiting
            // Still refused: nothing done, nothing logged again.
            if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) continue
            if (refused.remove(source, waiting)) start()
        }
    }

    /**
     * Runs [work] on the sources' thread; [latency] is read once it is written, in ms since the
     * change it answers. Any failure is counted and logged, never swallowed, and never kills the
     * process: it would start again on the same read, and crash again, the listener with it.
     */
    fun run(source: String, latency: () -> Long, work: suspend HubSources.() -> Int) = thread.execute {
        val written = try {
            runBlocking { sources.work() }
        } catch (e: RuntimeException) {
            return@execute diagnostics.failed(source, e)
        }
        diagnostics.synced(source, written, latency())
    }

    /**
     * Reads the source once, then at each change of [uris]. Changes arriving while a read waits
     * make one read; one arriving during a read makes the next.
     */
    private fun watch(source: String, permission: String, uris: List<Uri>, sync: suspend HubSources.() -> Int): (() -> Unit)? {
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            refused[source] = permission to { watch(source, permission, uris, sync)?.let { if (source == "texts") watchContacts(it) } }
            diagnostics.refused(source, permission)
            return null
        }
        val pending = AtomicBoolean()
        fun schedule() {
            if (!pending.compareAndSet(false, true)) return
            val seen = SystemClock.elapsedRealtime()
            run(source, { SystemClock.elapsedRealtime() - seen }) {
                pending.set(false)
                sync()
            }
        }
        // No handler: onChange comes on a binder thread, which only schedules.
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) = schedule()
        }
        uris.forEach { app.contentResolver.registerContentObserver(it, true, observer) }
        schedule()
        return ::schedule
    }

    /**
     * A contact added, renamed or deleted names the texts again at once ([AndroidRows.forgetNames],
     * then [readTexts]), without waiting for a new text. Without READ_CONTACTS the texts show their
     * numbers and nothing is observed (the provider would refuse the observer).
     */
    private fun watchContacts(readTexts: () -> Unit) {
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                rows.forgetNames()
                readTexts()
            }
        }
        try {
            app.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        } catch (e: SecurityException) {
            Log.w(TAG, "contacts not observed: ${e.javaClass.name}")
        }
    }
}

/**
 * What the sources did, for `adb shell dumpsys activity service dev.bold7.hub/.HubListener` and
 * `adb logcat -s Bold7/Hub`. Counts and times only: never a message, a name or a number.
 */
class Diagnostics {
    private data class Stats(
        val reads: Int = 0,
        val written: Int = 0,
        val lastLatencyMs: Long = -1,
        val failures: Int = 0,
        val lastFailure: String? = null,
    )

    private val stats = ConcurrentHashMap<String, Stats>()

    @Volatile private var listener = "not bound since the process started"

    fun listenerConnected() {
        listener = "bound since ${Instant.now()}"
        Log.i(TAG, "notification listener bound")
    }

    fun listenerDisconnected() {
        listener = "unbound since ${Instant.now()}"
        Log.w(TAG, "notification listener unbound: notifications no longer reach the Hub")
    }

    fun synced(source: String, written: Int, latencyMs: Long) {
        stats.compute(source) { _, s ->
            (s ?: Stats()).let { it.copy(reads = it.reads + 1, written = it.written + written, lastLatencyMs = if (written > 0) latencyMs else it.lastLatencyMs) }
        }
        if (written > 0) Log.i(TAG, "$source: $written written, $latencyMs ms after the change")
    }

    // Its class only: a message may hold data, a refusal's the URI of a PhoneLookup, a number.
    fun failed(source: String, e: RuntimeException) {
        val what = e.javaClass.simpleName
        stats.compute(source) { _, s -> (s ?: Stats()).let { it.copy(failures = it.failures + 1, lastFailure = what) } }
        Log.w(TAG, "$source failed: $what")
    }

    fun refused(source: String, permission: String) {
        stats[source] = Stats(failures = 1, lastFailure = "$permission not granted, source off until it is and the Hub is read")
        Log.w(TAG, "$source: $permission not granted, source off")
    }

    fun dump(out: PrintWriter) {
        out.println("Notification listener: $listener")
        stats.toSortedMap().forEach { (source, s) ->
            out.println(
                "$source: ${s.reads} reads, ${s.written} items written, last write ${s.lastLatencyMs} ms after its change, " +
                    "${s.failures} failures${s.lastFailure?.let { " (last: $it)" }.orEmpty()}",
            )
        }
    }
}
