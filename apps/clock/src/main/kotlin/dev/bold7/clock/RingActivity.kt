package dev.bold7.clock

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import dev.bold7.ui.Bold7Dialog
import java.time.LocalDateTime

/**
 * The ring (sim-162, sim-163, C): the clock's face and its bar behind, OS 7's dialog over them,
 * net_rim_alarm_reminder.png at its left, "Daily Alarm" and the time of the moment (it follows the
 * minutes, sim-163), Snooze (n min.) under the focus over Dismiss; Dismiss alone without a snooze
 * (guide p. 203) and for the timer (D). Over the lock screen, turning the screen on, kept on while
 * it rings. Escape snoozes, and without a snooze does nothing (D: a stray key never loses the alarm). It closes when the ring ends,
 * whoever ends it (RingService, also after [RingService.RING_LIMIT_MS]).
 */
class RingActivity : Activity() {
    private val graph get() = application.graph
    private lateinit var face: FaceView
    private var dialog: Dialog? = null
    private var shownMinute = -1
    private val onRinging: (Ringing?) -> Unit = { if (it == null) finish() else show(it) }
    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            graph.ringing?.let(::show)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        face = FaceView(this)
        face.setBackgroundColor(0xFF000000.toInt())
        setContentView(face)
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        graph.io.post {
            val images = ClockImages(this)
            mainExecutor.execute {
                face.images = images.face
                reminder = images.reminder
                shownMinute = -1
                graph.ringing?.let(::show)
            }
        }
    }

    private var reminder: android.graphics.Bitmap? = null

    override fun onStart() {
        super.onStart()
        graph.listeners += onRinging
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK))
        val r = graph.ringing
        if (r != null) {
            show(r)
        } else {
            // Opened by the notification after the ring, or before the service said it rings.
            face.postDelayed({ if (graph.ringing == null) finish() }, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        // OS 7's screen is up: Android's card over it goes (RingService.quiet); while it rings,
        // again after each Snooze, since every ring posts its card first.
        graph.ringScreen = true
        if (graph.ringing?.showcase != true) RingService.shown(this)
    }

    override fun onPause() {
        graph.ringScreen = false
        super.onPause()
    }

    override fun onStop() {
        graph.listeners -= onRinging
        unregisterReceiver(tick)
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    /** The face at the time of the moment and the dialog, made again when the minute changes. */
    private fun show(r: Ringing) {
        val now = LocalDateTime.now()
        face.bind(now.hour, now.minute, dayText(now.toLocalDate()), r.alarm, snoozed = false)
        val icon = reminder ?: return
        if (shownMinute == now.minute && dialog?.isShowing == true) return
        shownMinute = now.minute
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        val buttons = if (!r.timer && r.snooze > 0) listOf(getString(R.string.snooze, r.snooze), getString(R.string.dismiss)) else listOf(getString(R.string.dismiss))
        val title = getString(if (r.timer) R.string.timer else R.string.daily_alarm)
        dialog = Bold7Dialog(
            this, "$title\n%02d:%02d".format(now.hour, now.minute), buttons, null, 0,
            onButton = { i -> if (r.showcase) graph.ringing = null else if (buttons.size == 2 && i == 0) RingService.snooze(this) else RingService.dismiss(this) },
            // Without a snooze, Escape only puts the dialog back: Dismiss must be chosen.
            onCancel = {
                if (r.showcase) graph.ringing = null else if (buttons.size == 2) RingService.snooze(this) else face.post {
                    shownMinute = -1
                    graph.ringing?.let(::show)
                }
            },
            image = icon,
        ).also { it.show() }
    }
}
