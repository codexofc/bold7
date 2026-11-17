package dev.bold7.sysui

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

// The SystemUI classes installHeadsUp reaches by name (LineageOS 24); HeadsUpTargets.kt compiles
// the members against stubs/systemui.jar.
internal const val DECISION_PROVIDER = "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl"
internal const val LOGGABLE_DECISION = "$DECISION_PROVIDER\$LoggableDecision"
internal const val DECISION = "$DECISION_PROVIDER\$DecisionImpl"
internal const val NOTIFICATION_ENTRY = "com.android.systemui.statusbar.notification.collection.NotificationEntry"

/** Notification.FLAG_FSI_REQUESTED_BUT_DENIED, hidden from the SDK: Android keeps such a card up (sticky). */
internal const val FLAG_FSI_DENIED = 0x4000

/** The reason SystemUI's own log gives for a card cut here (VisualInterruptionDecisionLogger). */
internal const val CUT_REASON = "bold7: OS 7 shows no card"

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
 * Android's peek decision (a heads-up card, the screen awake), OS 7's way: no card over the screen
 * for a new notification (docs/BBOS7-VISUEL.md §5), only the sound, vibration, LED, the banner's
 * counters and the list. Never more than [android]: a card is kept for [kept] notifications, and
 * for every notification with the switch ([androidShade], asked last).
 */
fun headsUp(android: Boolean, kept: Boolean, androidShade: () -> Boolean): Boolean =
    android && (kept || androidShade())

/**
 * No heads-up card but for calls, alarms and full screen intents: one hook, after
 * VisualInterruptionDecisionProviderImpl.makeLoggablePeekDecision(NotificationEntry), read in the
 * SystemUI of LineageOS 24 (jadx). It is Android's only peek decision with the screen awake: both
 * makeAndLogHeadsUpDecision (HeadsUpCoordinator, each arrival and update) and
 * makeUnloggedHeadsUpDecision (the full screen intent's decision, which only matters for
 * notifications that have one, all kept) call it when the status bar is not dozing; dozing, they
 * take the pulse's path, untouched. Its answer is a LoggableDecision: a cut one is a new
 * LoggableDecision(DecisionImpl(CUT_REASON, false), null, null, false), which SystemUI logs as its
 * own. Android's heads_up_notifications_enabled cannot do it: it cuts the calls' cards too.
 * Nothing else changes: the notification is posted, sounds, vibrates, lights the LED, reaches the
 * Hub's listener and the list. A failure (counted) gives Android's answer, the card.
 */
internal fun installHeadsUp(loader: ClassLoader, targets: TargetResolution, after: (String, () -> Method, (Chain, Any?) -> Any?) -> Boolean) {
    // Found by the hook's lookup: anything missing is counted there, and SystemUI keeps its cards.
    var context: Field? = null
    var decision: Field? = null
    var interrupt: Field? = null
    var sbn: Field? = null
    var cut: Constructor<*>? = null
    var newDecision: Constructor<*>? = null
    var logged = false
    after("$DECISION_PROVIDER.makeLoggablePeekDecision(NotificationEntry) + context, LoggableDecision(...), DecisionImpl(String;Z)", {
        val provider = loader.loadClass(DECISION_PROVIDER)
        val loggable = loader.loadClass(LOGGABLE_DECISION)
        val impl = loader.loadClass(DECISION)
        context = provider.getField("context")
        decision = loggable.getField("decision")
        interrupt = impl.getField("shouldInterrupt")
        sbn = loader.loadClass(NOTIFICATION_ENTRY).getField("mSbn")
        cut = loggable.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
        newDecision = impl.getDeclaredConstructor(String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        targets.method(loader, DECISION_PROVIDER, "makeLoggablePeekDecision")
    }) { chain, result ->
        if (interrupt!!.get(decision!!.get(result)) != true) return@after result
        val n = (sbn!!.get(chain.getArg(0)) as StatusBarNotification).notification
        val kept = cardKept(n.fullScreenIntent != null, n.flags, n.extras.getString(Notification.EXTRA_TEMPLATE), n.category)
        val shown = headsUp(true, kept) {
            androidShadeAsked(Settings.Global.getInt((context!!.get(chain.thisObject) as Context).contentResolver, ANDROID_SHADE_SETTING, 0))
        }
        if (shown) return@after result
        if (!logged) {
            logged = true
            Log.i(TAG, "heads-up: Android's cards cut, calls, alarms and full screen intents kept")
        }
        cut!!.newInstance(newDecision!!.newInstance(CUT_REASON, false), null, null, false)
    }
}
