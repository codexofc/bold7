package dev.bold7.launcher.quickstep

import android.view.Choreographer
import dev.bold7.design.Fade
import dev.bold7.platform.OpeningAnimation
import dev.bold7.platform.OpeningWindow

/**
 * OS 7's window transitions ([Transitions]), played on the surface thread, paced by its vsync.
 */
object WindowZoom {
    /** An app opened from the home: it grows and fades in. */
    val open = animation(Transitions.OPEN)

    /**
     * An app left for the home: it shrinks and fades out over the home. Left from the switcher
     * (Home Screen, or End while it shows), the app goes at once and the switcher fades out over
     * the home ([Transitions.SWITCHER_TO_HOME], vid-04, C).
     */
    val close = OpeningAnimation { window ->
        if (window.leaves) {
            window.place(1f, 0f, 0f, 0f)
            fadeLeaving(window, Transitions.SWITCHER_TO_HOME)
        } else {
            play(window, Transitions.CLOSE)
        }
    }

    /**
     * An app chosen in the switcher: the app whole at once, under the switcher, which fades out
     * above it ([Transitions.SWITCHER_TO_APP], vid-04, C). No zoom: the theme has no rule from
     * application-switcher to an app, and application-switcher-animation-on = false (A).
     */
    val fromSwitcher = OpeningAnimation { window ->
        window.place(1f, 0f, 0f, 1f) // with the system's start transaction
        if (window.leaves) fadeLeaving(window, Transitions.SWITCHER_TO_APP) else window.finish()
    }

    /** Plays [fade] on the translucent windows leaving above [window]'s, then ends the transition. */
    private fun fadeLeaving(window: OpeningWindow, fade: Fade) {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            private var first = -1L

            override fun doFrame(nanos: Long) {
                try {
                    if (first < 0) first = nanos - VSYNC_NANOS
                    val ms = (nanos - first) / 1_000_000L
                    window.fadeLeaving(fade.alpha(ms))
                    if (ms >= fade.durationMs) window.finish() else Choreographer.getInstance().postFrameCallback(this)
                } catch (e: RuntimeException) {
                    window.fail(e)
                }
            }
        })
    }

    private fun animation(zoom: Zoom) = OpeningAnimation { play(it, zoom) }

    private fun play(window: OpeningWindow, zoom: Zoom) {
        place(window, zoom, 0f) // the first frame shown, with the system's start transaction
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            private var first = -1L

            override fun doFrame(nanos: Long) {
                try {
                    // p = 0 went with the system's start transaction: this vsync is the second frame.
                    if (first < 0) first = nanos - VSYNC_NANOS
                    val elapsed = nanos - first
                    place(window, zoom, zoom.progress(elapsed))
                    if (zoom.isOver(elapsed)) window.finish() else Choreographer.getInstance().postFrameCallback(this)
                } catch (e: RuntimeException) {
                    window.fail(e)
                }
            }
        })
    }

    private const val VSYNC_NANOS = 16_666_667L // the Q25 runs at 60 Hz only

    private fun place(window: OpeningWindow, z: Zoom, p: Float) {
        val s = z.scale(p)
        window.place(s, z.offset(window.width.toFloat(), s), z.offset(window.height.toFloat(), s), z.alpha(p))
    }
}
