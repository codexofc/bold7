package dev.bold7.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.ZonedDateTime

/**
 * Hands the next ring to AlarmManager. The alarm goes
 * through setAlarmClock: exact, run in Doze and in battery saver, shown as Android's next alarm
 * (the status bar's clock, Manage Connections' Alarm), and its receiver may start the ringing
 * foreground service from the background. The timer goes through setExactAndAllowWhileIdle on the
 * elapsed realtime clock: exact too, without Android's next alarm. Both need USE_EXACT_ALARM,
 * granted at install. AlarmManager forgets both at reboot: BootReceiver arms them again.
 * Called on the io thread.
 */
class AlarmScheduler(private val context: Context, private val store: ClockStore) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun broadcast(action: String) = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The next ring of the stored alarm and snooze, or none; returns it (wall clock ms). */
    fun arm(): Long? {
        val next = nextRing(store.settings.alarm, store.snoozeUntil, ZonedDateTime.now())
        val op = broadcast(AlarmReceiver.ALARM)
        if (next == null) {
            alarms.cancel(op)
        } else {
            val show = PendingIntent.getActivity(context, 0, Intent(context, ClockActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            // A SecurityException here (no exact alarm permission) is not caught: it must be seen.
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(next, show), op)
        }
        store.armedFor = next
        Log.i(TAG, "alarm armed for ${next?.let { java.time.Instant.ofEpochMilli(it) } ?: "nothing"}")
        return next
    }

    /** The timer's end, or none (elapsed realtime ms). */
    fun armTimer(endsAt: Long?) {
        val op = broadcast(AlarmReceiver.TIMER)
        if (endsAt == null) alarms.cancel(op) else alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endsAt, op)
    }
}
