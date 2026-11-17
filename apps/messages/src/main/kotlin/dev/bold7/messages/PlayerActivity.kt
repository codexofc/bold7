package dev.bold7.messages

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.VideoView
import dev.bold7.design.Durations
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.status
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeBitmap

/**
 * The player of a received video or sound, in the place of the system's app.
 * Guide 7.1 p. 139 (B): the controls at the bottom, previous, play or pause, stop, next, and the
 * progress bar slid by the trackpad; "if the media controls don't appear ... click the trackpad".
 * The bar is the theme's (bgcontrols_playpause_landscape.png: #000 at 80 %, #333 dividers, A, its
 * glyphs); the progress bar above it, the focus order, a ten-second slide and the bar hiding after
 * four seconds of play are mine (D, no capture). Escape closes. Plays on opening, as OS 7 does
 * when a file is clicked; the part is read by VideoView (MediaPlayer) with this app's own access.
 * Not exported, never over the lock screen; the clock is read only while the bar shows and the
 * file plays, nothing runs when paused or hidden.
 */
class PlayerActivity : Activity() {
    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TYPE = "type"
        private const val TICK_MS = 500L

        fun intent(context: Context, uri: String, type: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_URI, uri).putExtra(EXTRA_TYPE, type)
    }

    private lateinit var video: VideoView
    private lateinit var controls: Controls
    private var state = PlayerState()
    private var lastKey = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { onTick() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI) ?: return finish()
        val audio = intent.getStringExtra(EXTRA_TYPE).orEmpty().startsWith("audio/")
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return finish()
        video = VideoView(this).apply {
            setOnPreparedListener { state = state.prepared(it.duration); begin() }
            setOnCompletionListener { state = state.completed(); sync() }
            setOnErrorListener { _, _, _ -> status(this@PlayerActivity, getString(R.string.player_error)); postDelayed({ finish() }, Durations.STATUS_MS); true }
        }
        controls = Controls(this, audio)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            // A touch is the trackpad's click (D).
            setOnClickListener { press(PlayerKey.CLICK) }
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
        })
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        controls.bind(state)
        video.setVideoURI(Uri.parse(uri))
    }

    private fun begin() {
        lastKey = SystemClock.uptimeMillis()
        sync()
    }

    // The player follows the state's play flag; the clock runs only while the bar shows over a playing file.
    private fun sync() {
        if (state.playing && !video.isPlaying) video.start()
        if (!state.playing && video.isPlaying) video.pause()
        controls.bind(state)
        handler.removeCallbacks(tick)
        if (state.playing && state.shown) handler.postDelayed(tick, TICK_MS)
    }

    private fun onTick() {
        state = state.tick(video.currentPosition)
        if (SystemClock.uptimeMillis() - lastKey >= PlayerState.HIDE_MS) state = state.idle()
        sync()
    }

    private fun press(k: PlayerKey) {
        lastKey = SystemClock.uptimeMillis()
        val (next, effect) = state.key(k)
        state = next
        when (effect) {
            is PlayerEffect.Seek -> video.seekTo(effect.ms)
            PlayerEffect.Close -> return finish()
            null -> Unit
        }
        sync()
    }

    // Nothing plays behind another screen or a lock; coming back, the file waits for its click.
    override fun onPause() {
        state = state.copy(playing = false)
        if (::video.isInitialized) { video.pause(); handler.removeCallbacks(tick); controls.bind(state) }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val key = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount > 0) return true else PlayerKey.CLICK
            KeyEvent.KEYCODE_DPAD_LEFT -> PlayerKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerKey.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> PlayerKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PlayerKey.DOWN
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> PlayerKey.BACK
            else -> return super.dispatchKeyEvent(e)
        }
        press(key)
        return true
    }

    /** The bar and its progress line over the picture; a sound shows its icon on black. */
    private class Controls(context: Context, private val audio: Boolean) : View(context) {
        private var state = PlayerState()
        private val bar = fillPaint(0xCC000000.toInt())
        private val divider = fillPaint(0xFF333333.toInt())
        private val track = fillPaint(0xFF2D2E2F.toInt())
        private val trail = fillPaint(Color.WHITE)
        private val knob = fillPaint(0xFF3CA9E6.toInt())
        private val time = textPaint(Typography.SUBTITLE, Typography.REGULAR, Color.WHITE)
        private val bitmaps = HashMap<String, Bitmap>()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun bind(s: PlayerState) {
            if (s == state) return
            state = s
            invalidate()
        }

        private fun image(res: String) = bitmaps.getOrPut(res) { themeBitmap(context, res) }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (audio) image("msg_attachment_music").let { canvas.drawBitmap(it, (w - it.width) / 2, (h - it.height) / 2, paint) }
            if (!state.shown) return
            val top = h - BAR
            canvas.drawRect(0f, top, w, h, bar)
            val cell = w / 4
            for (i in 1..3) canvas.drawRect(cell * i - 1, top, cell * i, h, divider)
            val glyphs = listOf(
                Control.PREVIOUS to if (state.focus == Control.PREVIOUS) "msg_player_previous_focus" else "msg_player_previous",
                Control.PLAY_PAUSE to when {
                    state.playing -> if (state.focus == Control.PLAY_PAUSE) "msg_player_pause_focus" else "msg_player_pause"
                    else -> if (state.focus == Control.PLAY_PAUSE) "msg_player_play_focus" else "msg_player_play"
                },
                Control.STOP to if (state.focus == Control.STOP) "msg_player_stop_focus" else "msg_player_stop",
                Control.NEXT to "msg_player_next_disabled",
            )
            glyphs.forEachIndexed { i, (_, res) ->
                val b = image(res)
                canvas.drawBitmap(b, cell * i + (cell - b.width) / 2, top + (BAR - b.height) / 2, paint)
            }
            // The progress line above the bar, a knob on it while the slide is on; the times under it (D).
            val y = top - PROGRESS_BAND / 2f
            val left = MARGIN
            val right = w - MARGIN
            canvas.drawRect(0f, top - PROGRESS_BAND, w, top, bar)
            canvas.drawRect(left, y - 2, right, y + 2, track)
            val x = left + (right - left) * state.fraction
            canvas.drawRect(left, y - 2, x, y + 2, trail)
            if (state.focus == Control.PROGRESS) canvas.drawCircle(x, y, if (state.seeking) 9f else 6f, if (state.seeking) knob else trail)
            time.textAlign = Paint.Align.LEFT
            canvas.drawText(timeText(state.position), left, top - 4f, time)
            time.textAlign = Paint.Align.RIGHT
            canvas.drawText(timeText(state.duration), right, top - 4f, time)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean = false

        private companion object {
            const val BAR = 54
            const val PROGRESS_BAND = 40
            const val MARGIN = 14f
        }
    }
}
