package dev.bold7.essai

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView

/**
 * The measure of typing latency: the delay from a key's kernel
 * time (KeyEvent.eventTime, the evdev timestamp of the device that sent it: the real keyboard
 * without bold7-trackpad, its twin with it) to this activity, on Shift alone, so that nothing
 * typed is read. After [PRESSES] presses: median, 95th percentile and maximum, on the screen only.
 *
 * `su -c 'am start -n dev.bold7.essai/.KeyLatencyActivity'`
 */
class KeyLatencyActivity : Activity() {
    private val delays = mutableListOf<Long>()
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 24f; text = "Press Shift $PRESSES times" }
        setContentView(text)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_SHIFT_LEFT && keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        delays += SystemClock.uptimeMillis() - event.eventTime
        val sorted = delays.sorted()
        text.text = if (sorted.size < PRESSES) {
            "Shift ${sorted.size} / $PRESSES"
        } else {
            "Shift x${sorted.size}: median ${sorted[sorted.size / 2]} ms, p95 ${sorted[sorted.size * 95 / 100]} ms, max ${sorted.last()} ms"
        }
        return true
    }

    private companion object {
        const val PRESSES = 20
    }
}
