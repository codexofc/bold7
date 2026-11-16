package dev.bold7.launcher.quickstep

import dev.bold7.design.CubicBezier
import dev.bold7.design.Curves
import dev.bold7.design.Durations
import dev.bold7.design.Fade

// Pure math of OS 7's window transitions: no android.* here, tested on the JVM (ZoomTest).

/**
 * OS 7's zoom between two screens, TRANSITION_ZOOM of the 9900's theme.xml (A; its reading is D,
 * docs/BBOS7-VISUEL.md § 24). Entering, the window grows from [scale] to its full size while it
 * fades in; leaving, it goes from its full size to [scale] while it fades out; centred on itself.
 * Immutable: one instance serves every play, plain floats per frame.
 */
class Zoom(val scale: Float, val entering: Boolean, durationMs: Long, private val curve: CubicBezier) {
    private val durationNanos = durationMs * 1_000_000L

    /** Fraction of the way at [elapsedNanos] since the first frame, 1 once over. */
    fun progress(elapsedNanos: Long): Float = curve.transform(elapsedNanos.toFloat() / durationNanos)

    fun isOver(elapsedNanos: Long) = elapsedNanos >= durationNanos

    fun scale(p: Float) = if (entering) scale + (1f - scale) * p else 1f + (scale - 1f) * p

    fun alpha(p: Float) = if (entering) p else 1f - p

    /** Offset of the left (or top) edge that keeps a window [size] long centred at scale [s]. */
    fun offset(size: Float, s: Float) = size * (1f - s) / 2f
}

/**
 * The shape of OS 7's transitions, set here only. The 9900's theme gives the kind and the scale
 * (theme.xml, transitions of the screen « homescreen », A); duration, curve and the fade are D,
 * to be fixed by a video of the simulator.
 */
object Transitions {
    /** D: the homescreen's own animation-duration-portrait (A); the transitions give none. */
    const val DURATION_MS = Durations.PORTRAIT_MS

    /** D: the theme's curve for a change of view (animation-easingcurve-tabmanager, A). */
    val CURVE: CubicBezier = Curves.QuadraticOut

    /**
     * ATTR_SCALE 75 of both homescreen transitions (A). The UI Guidelines 7.1 (p. 40, B) say
     * « set the scale of the zoom to 110 percent » for the zoom between levels of an app; the
     * home's own rule, the device's and more specific, keeps 75 here, on the app that appears
     * (D). Duration: the guidelines give 150 ms to a
     * fade only, none to a zoom, so the home's 250 ms stays (D).
     */
    private const val HOME_SCALE = 0.75f

    /** Leaving the home (from homescreen, KIND_IN): the app grows from 75 % and fades in. */
    val OPEN = Zoom(HOME_SCALE, entering = true, DURATION_MS, CURVE)

    /** Back to the home (to homescreen, KIND_OUT): the app shrinks to 75 % and fades out. */
    val CLOSE = Zoom(HOME_SCALE, entering = false, DURATION_MS, CURVE)

    // Measured on the simulator (docs/BBOS7-VISUEL.md § 36, C).

    /**
     * The application switcher leaving (Escape, Menu, an app chosen): a fade of 150 ms (the
     * UI Guidelines 7.1 p. 40 fade, B; 116 to 190 ms measured, C), no change of scale, slowing
     * at the end as the opacity 0,3 ; 0,7 ; 0,9 ; 1 shows, fitted by QuadraticInOut (D; QuadraticOut starts too fast: 0,5). It appears as a cut.
     */
    val SWITCHER_CLOSE = Fade(1f, 0f, 150, Curves.QuadraticInOut)

    /** Manage Connections closing: the panel goes up in 190 ms (175 and 200 measured, C) at constant speed (1,8 to 2,6 px/ms), not a fade. Opening is a cut. */
    const val CONNECTIONS_CLOSE_MS = 190L
    val CONNECTIONS_CLOSE_CURVE: CubicBezier = Curves.Linear
}
