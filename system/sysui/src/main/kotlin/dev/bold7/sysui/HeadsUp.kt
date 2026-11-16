package dev.bold7.sysui

import android.app.Notification
import android.content.ContentResolver
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Field
import java.lang.reflect.Method

// The SystemUI classes installHeadsUp reaches by name; HeadsUpTest finds them in stubs/systemui.jar,
// HeadsUpTargets.kt compiles the members.
internal const val INTERRUPT_STATE_PROVIDER = "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl"
internal const val NOTIFICATION_ENTRY = "com.android.systemui.statusbar.notification.collection.NotificationEntry"

/** Notification.FLAG_FSI_REQUESTED_BUT_DENIED, hidden from the SDK: Android keeps such a card up (sticky). */
internal const val FLAG_FSI_DENIED = 0x4000

private const val CALL_STYLE = "android.app.Notification\$CallStyle"

/**
 * Whether a notification keeps Android's heads-up card (D): a call or an alarm
 * must never go unnoticed. A full screen intent (Phone's fallback, alarm clocks, other dialers),
 * one the app asked for and was denied, a CallStyle, the call and alarm categories.
 */
fun cardKept(fullScreenIntent: Boolean, flags: Int, template: String?, category: String?): Boolean =
    fullScreenIntent || flags and FLAG_FSI_DENIED != 0 || template == CALL_STYLE ||
        category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_ALARM

/**
 * NotificationInterruptStateProviderImpl.checkHeadsUp's answer, OS 7's way: no card over the screen
 * for a new notification (docs/BBOS7-VISUEL.md §5), only the sound, vibration, LED, the banner's
 * counters and the list. Never more than [android]: a card is kept for [kept] notifications, for
 * every notification with the switch ([androidShade], asked last), and [dozing], where Android's
 * answer means a pulse of the ambient display, not a card: none (no doze component,
 * system/overlays/android), kept for a SystemUI that would doze without our framework overlay.
 */
fun headsUp(android: Boolean, dozing: Boolean, kept: Boolean, androidShade: () -> Boolean): Boolean =
    android && (dozing || kept || androidShade())

/**
 * No heads-up card but for calls, alarms and full screen intents: one hook, after
 * NotificationInterruptStateProviderImpl.checkHeadsUp(NotificationEntry;Z)Z, read on the Q25's
 * MtkSystemUI. Its four callers are every heads-up decision (HeadsUpCoordinator's onEntryAdded,
 * onEntryUpdated, onRankingApplied) and the full screen intent's (NO_FSI_EXPECTED_TO_HUN), which
 * only asks for notifications that have one, all kept. Android's heads_up_notifications_enabled
 * cannot do it: read in the same method before anything else, it cuts the calls' cards too, and an
 * unlocked full screen intent then neither launches nor shows (NO_FSI_NO_HUN_OR_KEYGUARD).
 * Nothing else changes: the notification is posted, sounds, vibrates, lights the LED, reaches the
 * Hub's listener and the list. A failure (counted) gives Android's answer, the card.
 */
internal fun installHeadsUp(loader: ClassLoader, after: (String, () -> Method, (Chain, Any?) -> Any?) -> Boolean) {
    // Found by the hook's lookup: anything missing is counted there, and SystemUI keeps its cards.
    var state: Field? = null
    var dozing: Method? = null
    var resolver: Field? = null
    var sbn: Field? = null
    var logged = false
    after("$INTERRUPT_STATE_PROVIDER.checkHeadsUp(NotificationEntry;Z)Z + mStatusBarStateController.isDozing(), mContentResolver, NotificationEntry.mSbn", {
        val provider = loader.loadClass(INTERRUPT_STATE_PROVIDER)
        val entry = loader.loadClass(NOTIFICATION_ENTRY)
        state = provider.getField("mStatusBarStateController")
        dozing = state!!.type.getMethod("isDozing")
        resolver = provider.getField("mContentResolver")
        sbn = entry.getField("mSbn")
        provider.getDeclaredMethod("checkHeadsUp", entry, Boolean::class.javaPrimitiveType)
    }) { chain, result ->
        val android = result as Boolean
        if (!android) return@after false
        val self = chain.thisObject!!
        val n = (sbn!!.get(chain.getArg(0)) as StatusBarNotification).notification
        val kept = cardKept(n.fullScreenIntent != null, n.flags, n.extras.getString(Notification.EXTRA_TEMPLATE), n.category)
        val shown = headsUp(android, dozing!!.invoke(state!!.get(self)) as Boolean, kept) {
            androidShadeAsked(Settings.Global.getInt(resolver!!.get(self) as ContentResolver, ANDROID_SHADE_SETTING, 0))
        }
        if (!shown && !logged) {
            logged = true
            Log.i(TAG, "heads-up: Android's cards cut, calls, alarms and full screen intents kept")
        }
        shown
    }
}
