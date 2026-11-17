package dev.bold7.design

import kotlin.math.abs

/**
 * Cubic Bézier easing from (0, 0) to (1, 1), the shape of CSS `cubic-bezier()`, Android's
 * `PathInterpolator(x1, y1, x2, y2)` and Compose's `CubicBezierEasing`. [transform] maps elapsed
 * time (x) to progress (y).
 */
data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    init {
        require(x1 in 0f..1f && x2 in 0f..1f) { "x control points must be in [0, 1] for x(t) to be monotonic" }
    }

    fun transform(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        return sample(y1.toDouble(), y2.toDouble(), solveT(x.toDouble())).toFloat()
    }

    // Newton's method from t = x converges in a few steps on these curves; bisection covers the
    // flat spots where the slope vanishes.
    private fun solveT(x: Double): Double {
        val a = x1.toDouble()
        val b = x2.toDouble()
        var t = x
        repeat(8) {
            val error = sample(a, b, t) - x
            if (abs(error) < EPSILON) return t
            val slope = slope(a, b, t)
            if (abs(slope) < 1e-6) return@repeat
            t -= error / slope
        }
        var lo = 0.0
        var hi = 1.0
        t = x
        while (hi - lo > EPSILON) {
            if (sample(a, b, t) < x) lo = t else hi = t
            t = (lo + hi) / 2
        }
        return t
    }

    private companion object {
        const val EPSILON = 1e-7

        // B(t) = 3(1-t)²t·p1 + 3(1-t)t²·p2 + t³, in Horner form.
        fun sample(p1: Double, p2: Double, t: Double) = (((1 - 3 * p2 + 3 * p1) * t + (3 * p2 - 6 * p1)) * t + 3 * p1) * t

        fun slope(p1: Double, p2: Double, t: Double) = 3 * (1 - 3 * p2 + 3 * p1) * t * t + 2 * (3 * p2 - 6 * p1) * t + 3 * p1
    }
}

/**
 * Easing curves. BlackBerry OS 7.1 names Penner's equations in theme.xml (EASINGCURVE_*, A); they
 * are drawn here as the usual Bézier approximations (easings.net), within 0.05 of the equation
 * (see the tests).
 */
object Curves {
    /** Changing view in the tray (animation-easingcurve-tabmanager-portrait, A). */
    val QuadraticOut = CubicBezier(0.25f, 0.46f, 0.45f, 0.94f)

    /** The tray going up and down (animation-easingcurve-tabmanager-vertical, A). */
    val QuadraticInOut = CubicBezier(0.455f, 0.03f, 0.515f, 0.955f)

    /** The universal search appearing (animation-easingcurve-search, A); fades. */
    val Linear = CubicBezier(0f, 0f, 1f, 1f)

    /**
     * A fade that starts slowly: the short menu closing, 160 to 225 ms at the simulator, slow at
     * first (docs/BBOS7-VISUEL.md §36, C); the shape of the curve is the standard ease-in cubic (D).
     */
    val CubicIn = CubicBezier(0.55f, 0.055f, 0.675f, 0.19f)
}

/** Durations of theme.xml (A, BBOS7-VISUEL §2, §9), in ms. */
object Durations {
    /** Changing view in the tray (animation-duration-tabmanager-portrait). */
    const val TRANSITION_MS = 300L

    /** The app's name fading into the tab and out of it (tab-label-animation-duration-intro, -outro). */
    const val TAB_LABEL_FADE_MS = 200L

    /** How long the app's name stays in the tab (tab-label-animation-duration-roll-over). */
    const val TAB_LABEL_HOLD_MS = 3000L

    /** tab-label-animation-duration-pane-inactivity. */
    const val PANE_INACTIVITY_MS = 550L

    /** An app's status message (sim-42), by eye. */
    const val STATUS_MS = Eyeballed.STATUS_MS

    /** animation-duration-portrait. */
    const val PORTRAIT_MS = 250L

    /** The universal search appearing (animation-duration-search), linear. */
    const val SEARCH_MS = 400L
}


/**
 * A fade of a whole view from opacity [from] to [to] over [durationMs], along [curve] (the curve
 * shapes the time, not the opacity: the opacity is [from] + ([to] - [from]) x curve). Pure: the
 * views play it with a ViewPropertyAnimator.
 */
data class Fade(val from: Float, val to: Float, val durationMs: Long, val curve: CubicBezier) {
    /** The opacity [elapsedMs] after the start, [to] once over. */
    fun alpha(elapsedMs: Long): Float = from + (to - from) * curve.transform(elapsedMs.toFloat() / durationMs)
}
