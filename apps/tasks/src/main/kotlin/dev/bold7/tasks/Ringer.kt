package dev.bold7.tasks

import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import dev.bold7.showcase.showcaseOn
import dev.bold7.ui.Bold7Dialog
import java.time.Instant
import java.time.ZoneId

/**
 * The tasks' reminders on Android; what rings
 * when is [ringAt]'s, pure. Every call is made on the graph's io thread, which keeps them in order.
 *
 * One alarm of AlarmManager waits for the nearest reminder, none when no task has one to ring.
 * setExactAndAllowWhileIdle on RTC_WAKEUP, the calendar provider's own way for its reminders: exact,
 * allowed in Doze, permitted by USE_EXACT_ALARM (granted at install). Not setAlarmClock, the Clock's:
 * a task's reminder would become Android's next alarm, the status bar's alarm and Manage Connections'
 * Alarm ON. AlarmManager forgets it at reboot and when the app is force stopped: [sweep] runs again
 * at BOOT_COMPLETED, after an update, a change of time or zone, and at every opening of the list,
 * and rings late what it finds missed.
 */
class Ringer(private val context: Context, private val store: TaskStore) {
    companion object {
        const val RING = "dev.bold7.tasks.RING"
        /** The channel of the reminders: vibration only, as OS 7's Normal profile. The old "reminders" channel (with a sound) is deleted. */
        const val CHANNEL = "reminders-vibrate"
        private const val OLD_CHANNEL = "reminders"
    }

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /**
     * Rings every reminder whose time has come (notification first, then the task written as shown:
     * a crash in between rings it twice rather than never), then hands the next one to AlarmManager.
     * The tasks rung, for the box.
     */
    fun sweep(why: String): List<Task> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val due = ringing(store.all(), now, zone)
        for (t in due) {
            notify(t)
            if (store.save(t.shown()) == null) Log.e(TAG, "task ${t.id}: its reminder rang but could not be written as shown")
        }
        val next = arm(nextRing(store.all(), now, zone))
        // The on-device test procedure reads these log lines.
        Log.i(TAG, "reminders ($why): ${due.size} rung, next ${next?.let(Instant::ofEpochMilli) ?: "none"}")
        if (due.isNotEmpty() && !notifications.areNotificationsEnabled()) Log.e(TAG, "notifications refused: the reminders ring without sound nor notification (tools/install.sh)")
        return due
    }

    private fun arm(at: Long?): Long? {
        val op = PendingIntent.getBroadcast(context, 0, Intent(context, AlarmReceiver::class.java).setAction(RING), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        when {
            at == null -> alarms.cancel(op)
            alarms.canScheduleExactAlarms() -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            else -> {
                // Never without an alarm: late by Android's window rather than lost.
                Log.e(TAG, "exact alarms refused: the reminder may ring late")
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            }
        }
        return at
    }

    /**
     * The reminder's notification. No sound: in OS 7's Normal profile the Tasks volume is "Silent",
     * the reminder vibrates only (BBOS7-VISUEL §20, sim-38 to sim-40, C, the
     * rule being to follow the simulator); the box and the vibration say it rang. Default importance: a
     * line in the list, no Android card over the screen (the box is OS 7's face). Private on the lock
     * screen: "Task Reminder" only.
     */
    private fun notify(t: Task) {
        if (notifications.getNotificationChannel(CHANNEL) == null) {
            notifications.deleteNotificationChannel(OLD_CHANNEL)
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.task_reminder), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
        val open = PendingIntent.getActivity(context, t.id.toInt(), ReminderActivity.intent(context, t.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(context.getString(R.string.task_reminder)).build()
        notifications.notify(
            t.id.toInt(),
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(t.title.ifEmpty { context.getString(R.string.no_title) })
                .setContentText(t.due?.let { context.getString(R.string.box_due, dateTimeLabel(it)) })
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The task's notification gone once its box is answered. */
    fun cancel(id: Long) = notifications.cancel(id.toInt())
}

/** Runs [Ringer.sweep] on the io thread, then opens the box over the tasks rung. */
fun sweep(context: Context, why: String, then: () -> Unit = {}) {
    val graph = context.graph
    graph.io.post {
        val rung = try {
            // The showcase's tasks never ring, and the real ones wait for its end.
            if (showcaseOn(context)) emptyList() else graph.ringer.sweep(why)
        } finally {
            then()
        }
        if (rung.isNotEmpty()) context.mainExecutor.execute {
            graph.waiting.addAll(rung.map { it.id }.filter { it !in graph.waiting })
            try {
                context.startActivity(Intent(context, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: RuntimeException) {
                // Without SYSTEM_ALERT_WINDOW (tools/install.sh) Android refuses a start from the background.
                Log.w(TAG, "reminder box not opened; the notification opens it", e)
            }
        }
    }
}

/** AlarmManager's alarm (not exported: only our PendingIntent reaches it). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Ringer.RING) return
        val done = goAsync()
        sweep(context, "alarm") { done.finish() }
    }
}

/**
 * The system's broadcasts after which the alarm must be handed again (exported: Android only
 * delivers these to an exported receiver). Every action here is a protected broadcast, which
 * Android refuses from any sender but itself; any other action is ignored, and the only effect is
 * to ring what is due and arm again what the app already keeps: the same exception to the export
 * rule as the Clock's BootReceiver. BOOT_COMPLETED only, not LOCKED_BOOT_COMPLETED: the tasks are in the
 * credential encrypted storage, read after the first unlock; a reminder due before it rings then.
 */
class BootReceiver : BroadcastReceiver() {
    private val actions = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in actions) return
        val done = goAsync()
        sweep(context, action.substringAfterLast('.')) { done.finish() }
    }
}

/**
 * The reminder box, the Calendar's (sim-245, C there, D for Tasks; net_rim_task_reminder.png, A):
 * the task's title and its due date, Open, Dismiss (focused) and Snooze (Tasks Options' Snooze;
 * none when it is None, as the Clock's). Escape snoozes, as the Calendar's (D). Not showWhenLocked:
 * started while locked, it waits behind the lock screen (nothing interactive while locked,
 * CONTRIBUTING.md). One box after the other while several wait.
 */
class ReminderActivity : Activity() {
    companion object {
        private const val EXTRA_ID = "id"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, ReminderActivity::class.java).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val graph get() = application.graph
    private var dialog: Dialog? = null
    private var icon: Bitmap? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        take(intent)
        graph.io.post {
            val i = BitmapFactory.decodeResource(resources, R.drawable.tsk_reminder)
            mainExecutor.execute {
                icon = i
                next()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
        if (dialog == null && icon != null) next()
    }

    /** A notification's tap brings its task first. */
    private fun take(i: Intent) {
        val id = i.getLongExtra(EXTRA_ID, -1)
        if (id >= 0) graph.waiting.apply { remove(id); addFirst(id) }
    }

    private fun next() {
        if (busy) return
        val id = graph.waiting.removeFirstOrNull() ?: return finish()
        busy = true
        val g = graph
        g.io.post {
            val t = if (showcaseOn(this)) null else g.store.get(id)
            val snooze = g.options.snooze
            mainExecutor.execute {
                busy = false
                if (t == null) return@execute next()
                show(t, snooze)
            }
        }
    }

    private fun show(t: Task, snooze: Int) {
        val text = listOfNotNull(t.title.ifEmpty { getString(R.string.no_title) }, t.due?.let { getString(R.string.box_due, dateTimeLabel(it)) }).joinToString("\n")
        val buttons = listOfNotNull(getString(R.string.open), getString(R.string.dismiss), if (snooze > 0) getString(R.string.snooze, snooze) else null)
        dialog = Bold7Dialog(
            this, text, buttons, null, focused = 1,
            onButton = { i ->
                when (i) {
                    0 -> answer(t, null).also { startActivity(EditActivity.open(this, t.id)) }
                    1 -> answer(t, null)
                    else -> answer(t, snooze)
                }
            },
            onCancel = { answer(t, snooze.takeIf { it > 0 }) },
            image = icon,
        ).also { it.show() }
    }

    /** Dismissed ([minutes] null) or snoozed: the notification gone, the alarm armed again, the next box. */
    private fun answer(t: Task, minutes: Int?) {
        dialog = null
        val g = graph
        g.ringer.cancel(t.id)
        if (minutes != null) g.io.post {
            g.store.get(t.id)?.let { g.store.save(it.snoozed(System.currentTimeMillis(), minutes)) }
            g.ringer.sweep("snooze")
        }
        next()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }
}
