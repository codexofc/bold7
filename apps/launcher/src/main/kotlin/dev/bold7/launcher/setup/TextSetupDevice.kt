package dev.bold7.launcher.setup

import android.app.Activity
import android.app.ActivityOptions
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri

private const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"

/** The bridge's request in Google Messages' process (system/sysui, MessagesBridge.kt), and the page it answers with. */
private const val OPEN_CONSENT_ACTION = "dev.bold7.action.OPEN_TEXT_CONSENT"
private const val CONSENT_PAGE_EXTRA = "page"

/** Messages of Bold7 answers the state of the switch on this authority (call "consent", result key "on"); without it the state is UNKNOWN. */
internal const val CONSENT_AUTHORITY = "dev.bold7.messages.consent"
private const val PREFS = "text_setup"
private const val ASKED = "asked"

/** The device side of the screen: all of it blocking, off the main thread only. */
class TextSetupDevice(private val context: Context) {
    private val prefs by lazy { context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun messagesInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(GOOGLE_MESSAGES, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun asked(): Boolean = prefs.getBoolean(ASKED, false)

    fun markAsked() = prefs.edit().putBoolean(ASKED, true).apply()

    /** Whether Messages of Bold7 can talk to Google Messages: asked of it, never read from Google's own files. */
    fun consent(): Consent = try {
        val answer = context.contentResolver.call(Uri.parse("content://$CONSENT_AUTHORITY"), "consent", null, null)
        when (answer?.getBoolean("on")) {
            true -> Consent.ON
            false -> Consent.OFF
            null -> Consent.UNKNOWN
        }
    } catch (_: RuntimeException) {
        Consent.UNKNOWN
    }

    /**
     * Google's page of the switch, in this screen's task, else Google Messages itself; [opened]
     * false when neither opens. The page is not exported: the bridge of dev.bold7.sysui, in Google
     * Messages' process, answers an ordered broadcast (OPEN_TEXT_CONSENT, which this app alone
     * holds, and its identity shared) with a PendingIntent of Google Messages for that page only,
     * immutable. Without the bridge (not installed, Google Messages not running) nobody answers.
     * Main thread.
     */
    fun openPage(from: Activity, opened: (Boolean) -> Unit) {
        val ask = Intent(OPEN_CONSENT_ACTION).setPackage(GOOGLE_MESSAGES)
        val identity = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        from.sendOrderedBroadcast(ask, null, identity, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (from.isDestroyed) return
                val page = getResultExtras(false)?.getParcelable(CONSENT_PAGE_EXTRA, PendingIntent::class.java)
                    ?.takeIf { it.creatorPackage == GOOGLE_MESSAGES }
                opened(page != null && startPage(from, page) || openMessages(from))
            }
        }, null, Activity.RESULT_CANCELED, null, null)
    }

    // Started from the activity, without a new task: Back on Google's page comes back to this screen.
    private fun startPage(from: Activity, page: PendingIntent): Boolean = try {
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        from.startIntentSender(page.intentSender, null, 0, 0, 0, options)
        true
    } catch (_: IntentSender.SendIntentException) {
        false
    }

    private fun openMessages(from: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(GOOGLE_MESSAGES) ?: return false
        return try {
            from.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
