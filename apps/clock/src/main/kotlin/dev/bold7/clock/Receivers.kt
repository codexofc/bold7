package dev.bold7.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log

/**
 * AlarmManager's rings (not exported: only our PendingIntents reach it). The next ring is armed
 * first, so that a ring which fails to sound never takes the next day's with it; then the ringing
 * service starts, a partial wake lock bridging the two (released by the service, or after a minute).
 */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ALARM = "dev.bold7.clock.ALARM"
        const val TIMER = "dev.bold7.clock.TIMER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ALARM && action != TIMER) return
        RingService.holdCpu(context)
        val pending = goAsync()
        val graph = context.graph
        graph.io.post {
            try {
                if (action == ALARM) {
                    graph.store.snoozeUntil = null
                    graph.scheduler.arm()
                } else {
                    graph.store.timer = graph.store.timer.reset()
                }
                RingService.ring(context, timer = action == TIMER)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The system's broadcasts after which AlarmManager's rings must be handed again (exported: Android
 * only delivers these to an exported receiver). Every action here is a protected broadcast, which
 * Android refuses from any sender but itself ("Permission Denial: not allowed to send broadcast");
 * any other action is ignored, and the only effect is to arm again what the Clock already keeps.
 *
 * - LOCKED_BOOT_COMPLETED (directBootAware): before the first unlock, so that a night reboot still rings.
 * - BOOT_COMPLETED: again after the unlock, harmless.
 * - MY_PACKAGE_REPLACED: an update of the app.
 * - TIME_SET, TIMEZONE_CHANGED: the alarm is a wall clock time, its instant moves.
 *
 * At boot, a ring missed by the reboot rings now ([missed]), the alarm stream's volume left by a
 * ring cut short is given back, and the stopwatch and the timer, on the elapsed realtime clock that
 * a reboot sets back to zero, are reset.
 */
class BootReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
    )

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in actions) return
        val pending = goAsync()
        val graph = context.graph
        graph.io.post {
            try {
                val store = graph.store
                val boot = action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_BOOT_COMPLETED
                if (boot && graph.ringing == null) {
                    store.savedVolume?.let {
                        context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it.toInt(), 0)
                        store.savedVolume = null
                    }
                    if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                        store.stopwatch = Stopwatch()
                        store.timer = store.timer.reset()
                    }
                }
                val armed = store.armedFor
                val ringNow = boot && missed(armed, System.currentTimeMillis())
                graph.scheduler.arm()
                if (ringNow) {
                    Log.w(TAG, "the ring of $armed was missed by a reboot: ringing now")
                    RingService.holdCpu(context)
                    RingService.ring(context, timer = false)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

internal fun wakeLock(context: Context): PowerManager.WakeLock =
    context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bold7:clock").apply { setReferenceCounted(false) }
