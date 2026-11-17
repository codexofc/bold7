package dev.bold7.sysui

import android.app.NotificationManager
import android.content.Context
import android.media.AsyncPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.lang.reflect.Field

// The SystemUI classes installBattery reaches by name (LineageOS 24, read with jadx); BatteryTargets.kt
// compiles the members against stubs/systemui.jar.
internal const val POWER_WARNINGS = "com.android.systemui.power.PowerNotificationWarnings"
internal const val ANIMATION_SCHEDULER = "com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerImpl"
internal const val STATUS_EVENT = "com.android.systemui.statusbar.events.StatusEvent"
internal const val BATTERY_EVENT = "com.android.systemui.statusbar.events.BatteryEvent"

/**
 * Whether the status bar's event chip for [event] (a StatusEvent's class name) is left out: the
 * battery's, which Android slides in with the level when a charger is plugged in. The 9900 shows
 * no such thing: its banner's battery takes the plug (sim-415, C), nothing more.
 */
fun chipLeftOut(event: String): Boolean = event == BATTERY_EVENT

/**
 * Whether OS 7's low battery tone plays, as Android's notification would have played it: with the
 * ringer on and nothing held back by Do Not Disturb (Bold7's Silent, Vibrate Only and Phone Calls
 * Only profiles turn one or the other).
 */
fun lowBatteryTone(ringerMode: Int, filter: Int): Boolean =
    ringerMode == AudioManager.RINGER_MODE_NORMAL &&
        (filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)

/**
 * Android's battery where the 9900 has none, two hooks:
 * - SystemStatusAnimationSchedulerImpl.onStatusEvent(StatusEvent)V, instead of Android's for a
 *   BatteryEvent: no chip of the battery and its level in an app's bar when a charger is plugged
 *   in ([chipLeftOut]); the privacy and display events go on.
 * - PowerNotificationWarnings.updateNotification()V, instead of Android's while it warns of a low
 *   battery: no notification (title, level, Battery Saver buttons), which reached OS 7's list of
 *   the banner. The 9900 warns with its amber LED (guide 7.1 p. 15, M), its red battery
 *   (Strips, F) and its event sound (p. 163, M): the tone the notification carried
 *   (low_battery_sound, Notifier_BatteryLow of the firmware) plays alone when Android would have
 *   played it ([lowBatteryTone]). The invalid charger's notice, the auto saver's suggestion and
 *   the dismissal stay Android's.
 * A failure (counted) gives Android's.
 */
internal fun installBattery(loader: ClassLoader, instead: (String, () -> Executable, (Chain) -> Boolean) -> Boolean) {
    instead("$ANIMATION_SCHEDULER.onStatusEvent(StatusEvent)V + $BATTERY_EVENT", {
        loader.loadClass(BATTERY_EVENT).getField("batteryLevel")
        loader.loadClass(ANIMATION_SCHEDULER).getDeclaredMethod("onStatusEvent", loader.loadClass(STATUS_EVENT))
    }) { chain -> chipLeftOut(chain.getArg(0)!!.javaClass.name) }

    lateinit var fields: List<Field>
    // AsyncPlayer plays on its own thread, off SystemUI's main one, and releases the file at the end.
    val player = AsyncPlayer("Bold7/Battery")
    instead("$POWER_WARNINGS.updateNotification()V + mWarning, mInvalidCharger, mPlaySound, mContext", {
        val cls = loader.loadClass(POWER_WARNINGS)
        fields = listOf("mWarning", "mInvalidCharger", "mPlaySound", "mContext").map { cls.getField(it) }
        cls.getDeclaredMethod("updateNotification")
    }) { chain ->
        val warnings = chain.thisObject!!
        val (warning, invalidCharger, playSound, context) = fields
        if (!warning.getBoolean(warnings) || invalidCharger.getBoolean(warnings)) return@instead false
        val c = context.get(warnings) as Context
        if (playSound.getBoolean(warnings)) {
            playSound.setBoolean(warnings, false)
            val ringer = c.getSystemService(AudioManager::class.java).ringerMode
            val filter = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter
            val file = Settings.Global.getString(c.contentResolver, "low_battery_sound")
            if (lowBatteryTone(ringer, filter) && file != null) {
                player.play(c, Uri.parse("file://$file"), false, AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            }
            Log.i(TAG, "battery: low, no notification, tone ${if (lowBatteryTone(ringer, filter)) "played" else "held (ringer $ringer, filter $filter)"}")
        }
        // Simplification: one posted before the hook (Bold7 installed while the battery was low) stays
        // until Android dismisses the warning at the charger, through the original path.
        true
    }
}
