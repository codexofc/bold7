package dev.bold7.launcher.quickstep

import android.view.Choreographer
import dev.bold7.platform.OpeningAnimation
import dev.bold7.platform.OpeningWindow

/**
 * OS 7's window transitions ([Transitions]), played on the surface thread, paced by its vsync.
 */
object WindowZoom {
    /** An app opened from the home: it grows and fades in. */
    val open = animation(Transitions.OPEN)

    /** An app left for the home: it shrinks and fades out over the home. */
    val close = animation(Transitions.CLOSE)

    /**
     * No animation, the window whole at once: an app chosen in the switcher (no transition in the
     * theme from application-switcher to an app, and application-switcher-animation-on = false, A).
     */
    val cut = OpeningAnimation { window ->
        window.place(1f, 0f, 0f, 1f)
        window.finish()
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
