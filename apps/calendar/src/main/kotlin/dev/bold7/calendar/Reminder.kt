package dev.bold7.calendar

import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.themeBitmap
import java.time.ZoneId

/**
 * The calendar provider's reminders. Google Calendar stays the engine that rings and notifies; this receiver only opens OS 7's box, silent.
 *
 * Exported without a sender permission: the provider broadcasts EVENT_REMINDER implicitly, from its
 * own uid, without any permission Bold7 could require (framework of the Q25, CalendarAlerts.
 * scheduleAlarm). Declared exception to the exported components' rule, as the Clock's BootReceiver:
 * it believes nothing of a broadcast but an instant, reads the provider again and shows only a
 * reminder the provider holds at that instant, not shown before, never in the future ([toShow]).
 * [ACTION_SNOOZED] is its own alarm's, after a Snooze: the same reading, for that one reminder.
 */
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SNOOZED = "dev.bold7.calendar.SNOOZED"
        private const val EXTRA_ALARM = "alarmTime"
        private const val EXTRA_KEY = "key"

        /** The box of [alert] again in [SNOOZE_MINUTES]: an alarm that does not ring, allowed while idle, inexact. */
        fun snooze(context: Context, alert: Alert) {
            val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_SNOOZED)
                .putExtra(EXTRA_ALARM, alert.alarmTime).putExtra(EXTRA_KEY, alert.key)
            val pending = PendingIntent.getBroadcast(context, alert.key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, snoozedUntil(System.currentTimeMillis()), pending)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val snoozed = intent.action == ACTION_SNOOZED
        val alarmTime = when {
            snoozed -> intent.getLongExtra(EXTRA_ALARM, -1)
            intent.action == CalendarContract.ACTION_EVENT_REMINDER && intent.data?.authority == AUTHORITY -> intent.data?.lastPathSegment?.toLongOrNull() ?: -1
            else -> -1
        }
        if (alarmTime < 0) return
        val key = intent.getStringExtra(EXTRA_KEY)
        val graph = context.graph
        val done = goAsync()
        graph.io.post {
            try {
                val now = System.currentTimeMillis()
                val held = graph.agenda.alerts(alarmTime)
                val show = if (snoozed) held.filter { it.key == key && it.alarmTime <= now } else toShow(held, alarmTime, now, graph.shown)
                graph.shown = remember(graph.shown, show, now)
                // Logged so that the reminders received can be counted against Google Calendar's own notifications.
                Log.i(TAG, "reminder received: ${if (snoozed) "snooze" else "provider"}, ${held.size} held at the instant, ${show.size} to show")
                if (show.isEmpty()) return@post
                context.mainExecutor.execute {
                    graph.waiting.addAll(show)
                    try {
                        context.startActivity(Intent(context, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: RuntimeException) {
                        // Without SYSTEM_ALERT_WINDOW (tools/install.sh) Android refuses a start from the background.
                        Log.w(TAG, "reminder box not opened; Google Calendar's notification still rings", e)
                    }
                }
            } finally {
                done.finish()
            }
        }
    }
}

/**
 * OS 7's reminder box (D until captured: the Clock's ring box, sim-162, with the calendar's
 * net_rim_calendar_reminder.png): the subject, the time and the place, Open, Snooze and Dismiss.
 * Not showWhenLocked: started while locked, it waits behind the lock screen (nothing interactive
 * while locked, CONTRIBUTING.md). One box after the other while several wait.
 */
class ReminderActivity : Activity() {
    private val graph get() = application.graph
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph.io.post {
            val icon = themeBitmap(applicationContext, "cal_reminder")
            mainExecutor.execute { next(icon) }
        }
    }

    private fun next(icon: android.graphics.Bitmap?) {
        val alert = graph.waiting.removeFirstOrNull() ?: return finish()
        val zone = ZoneId.systemDefault()
        val text = (listOf(Texts.subject(alert.title, alert.location, getString(R.string.no_subject))) + Texts.reminderLines(alert.begin, alert.end, alert.allDay, zone)).joinToString("\n")
        val buttons = listOf(getString(R.string.open), getString(R.string.dismiss), getString(R.string.snooze, SNOOZE_MINUTES))
        dialog = Bold7Dialog(
            this, text, buttons, null, REMINDER_FOCUS,
            onButton = { i ->
                when (ReminderButton.entries[i]) {
                    ReminderButton.OPEN -> startActivity(EventActivity.open(this, alert.eventId, alert.begin, alert.end))
                    ReminderButton.SNOOZE -> ReminderReceiver.snooze(this, alert)
                    ReminderButton.DISMISS -> Unit
                }
                next(icon)
            },
            // Escape keeps the reminder: it comes back as a Snooze (D, as the Clock's ring).
            onCancel = {
                ReminderReceiver.snooze(this, alert)
                next(icon)
            },
            image = icon,
        ).also { it.show() }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }
}
