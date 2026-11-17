package dev.bold7.launcher.hub

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import android.widget.Toast
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.SHOWCASE_KEY
import dev.bold7.hub.data.ItemKind
import dev.bold7.launcher.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a tap on a row goes: the item in its own app. */
sealed interface Opening {
    /** The notification's contentIntent while it lives, else its [app]. */
    data class Notification(val key: String, val app: String?) : Opening

    /** The SMS app on the conversation with [address] (a group opens on its sender, D). */
    data class Conversation(val address: String?) : Opening

    /** OS 7's "Call [number]?" (sim-104), asked by the Phone of Bold7. */
    data class Call(val number: String) : Opening

    /** The Phone on its log: a missed call without number. */
    data object CallLog : Opening

    data class Event(val id: Long) : Opening

    data object None : Opening
}

/** What a tap on a row came to, for the log and for the toast of a row that opens nothing. */
enum class Opened { NOTIFICATION, APP, CONVERSATION, CALL_PROMPT, CALL_LOG, EVENT, UNAVAILABLE }

/**
 * A notification's tap: its own contentIntent if it could be [sent]; else, gone, the app that
 * posted it if that app has a LAUNCHER activity ([appStarts]); nothing else is guessed (a
 * notification of "android" names no app to open), and the row says it cannot open.
 */
fun notificationOpened(sent: Boolean, appStarts: Boolean): Opened = when {
    sent -> Opened.NOTIFICATION
    appStarts -> Opened.APP
    else -> Opened.UNAVAILABLE
}

fun opening(item: HubItem): Opening = when (item.kind) {
    ItemKind.NOTIFICATION -> item.conversationId?.let { Opening.Notification(it, item.folder) } ?: Opening.None
    ItemKind.SMS, ItemKind.MMS -> Opening.Conversation(item.address)
    ItemKind.MISSED_CALL -> item.address?.takeIf { it.isNotBlank() }?.let(Opening::Call) ?: Opening.CallLog
    ItemKind.INVITATION -> item.sourceKey.toLongOrNull()?.let(Opening::Event) ?: Opening.None
    // No mail source yet.
    ItemKind.EMAIL -> Opening.None
}

/**
 * Opens a Hub item in its own app: the Hub page's rows and the
 * home's list of notifications. Nothing while the phone is locked (CONTRIBUTING.md).
 *  */
internal class ItemOpener(
    private val context: Context,
    private val reader: AndroidHubReader,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    /** Starts an app's activity as the home starts them (HomeView); false if nothing started. */
    private val start: (Intent) -> Boolean,
    /** The options of a start from the home: OS 7's zoom (RemoteLaunches.activityOptions). */
    private val homeOptions: () -> ActivityOptions,
) {
    fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Opens [item], [label] naming it if it cannot open; [then] gets what came of it, on the main thread. */
    fun open(item: HubItem, label: String, then: (Opened) -> Unit = {}) {
        if (locked()) return
        if (item.sourceKey.startsWith("demo:") || item.sourceKey.startsWith(SHOWCASE_KEY)) return // the made-up items of the measure and of the showcase
        fun done(opened: Opened) {
            Log.i(TAG, "open ${item.kind}: $opened")
            // A toast for an item that cannot open (D: no OS 7 text for it).
            if (opened == Opened.UNAVAILABLE) Toast.makeText(context, context.getString(R.string.hub_unable_to_open, label), Toast.LENGTH_SHORT).show()
            then(opened)
        }
        fun done(opened: Opened, started: Boolean) = done(if (started) opened else Opened.UNAVAILABLE)
        when (val o = opening(item)) {
            is Opening.Notification -> scope.launch {
                val intent = withContext(io) { reader.open(o.key) }
                val sent = intent != null && send(intent)
                val app = if (sent) null else o.app?.let { appIntent(it) }
                val opened = notificationOpened(sent, app != null)
                done(if (opened == Opened.APP && !start(app!!)) Opened.UNAVAILABLE else opened)
            }
            is Opening.Conversation -> done(
                Opened.CONVERSATION,
                start(
                    o.address?.let { Intent(Intent.ACTION_VIEW, Uri.fromParts("sms", it, null)) }
                        ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                ),
            )
            // OS 7's "Call …?" over the list (sim-104), asked by the Phone of Bold7 (PromptActivity,
            // CALL_PROMPT); without it, the Phone that holds DIALER on its log.
            is Opening.Call -> if (prompt(o.number)) {
                done(Opened.CALL_PROMPT)
            } else {
                done(Opened.CALL_LOG, start(Intent(Intent.ACTION_DIAL)))
            }
            Opening.CallLog -> done(Opened.CALL_LOG, start(Intent(Intent.ACTION_DIAL)))
            // The launcher reads the calendar: it lends the app that read (StrictMode kills a debug
            // build that hands over a content URI without it).
            is Opening.Event -> done(
                Opened.EVENT,
                start(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, o.id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)),
            )
            Opening.None -> done(Opened.UNAVAILABLE)
        }
    }

    /** Starts [app]'s launcher activity, if it has one; nothing while locked. */
    fun openApp(app: String) {
        if (locked()) return
        scope.launch { appIntent(app)?.let(start) }
    }

    /**
     * The Phone's "Call [number]?", a dialog over the list without an opening animation (sim-104:
     * the list stays behind, the home is not stopped); false if the Phone of Bold7 is not there
     * or refuses (CALL_PROMPT not granted).
     */
    private fun prompt(number: String): Boolean = try {
        val intent = Intent(CALL_PROMPT, Uri.fromParts("tel", number, null)).setPackage(PHONE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent, ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle())
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Call prompt refused", e)
        false
    }

    private suspend fun appIntent(app: String): Intent? = withContext(io) { context.packageManager.getLaunchIntentForPackage(app) }

    /**
     * Sends a notification's contentIntent as the shade does; the home, on top, lends it its right
     * to start an activity (Android 14 asks the sender to say so). False if the app cancelled it.
     */
    private fun send(intent: PendingIntent): Boolean = try {
        fun send(options: ActivityOptions) = intent.send(
            context, 0, null, null, null, null,
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE).toBundle(),
        )
        try {
            send(homeOptions()) // leaving the home: OS 7's zoom, as an app of the grid
        } catch (e: SecurityException) {
            Log.w(TAG, "zoom refused for a notification, opened by Android", e)
            send(ActivityOptions.makeBasic())
        }
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    }

    private companion object {
        const val TAG = "Bold7/Hub"
        const val PHONE = "dev.bold7.phone"
        const val CALL_PROMPT = "dev.bold7.phone.CALL_PROMPT"
    }
}
