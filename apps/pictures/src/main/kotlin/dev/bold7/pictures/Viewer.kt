package dev.bold7.pictures

import kotlin.math.pow

// The viewer of Pictures, pure (docs/BBOS7-VISUEL.md §37.3). Measured on the simulator (sim-357 to
// sim-362, C): each click zooms by 1.18 around the screen's centre, six times at most (2.70 times
// the fitted picture); Escape zooms out a step, and from the fitted picture closes it; zoomed, a
// step of the trackpad pans 64 px of the screen; fitted, the trackpad goes to the previous or the
// next picture. The guide's keys (7.1 p. 55, B): I and O zoom, 2, 4, 6, 8 pan, L rotates.
//
// Frame and Zoom follow apps/messages' Viewer.kt with the 9900's steps; they could move to
// core/ui as one shared viewer if a third user appears.

/** One click's zoom (sim-358, sim-359, C: 1.18 matches to 0.022 RMSE, 1.17 and 1.19 do not). */
const val ZOOM_STEP = 1.18f

/** The deepest zoom: six clicks (sim-362, C: the seventh changes nothing). */
const val MAX_LEVEL = 6

/** A step of the trackpad when zoomed, in screen pixels (sim-360, sim-361, C: 64 on the 9900's 640). */
const val PAN_STEP = 64

/** A picture of [width] by [height], turned [quarter] quarter turns clockwise, on a screen of [screenWidth] by [screenHeight]. */
data class Frame(val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int, val quarter: Int = 0) {
    /** Its sides once turned. */
    val shownWidth: Int get() = if (quarter % 2 == 0) width else height
    val shownHeight: Int get() = if (quarter % 2 == 0) height else width

    /** The whole picture in the screen, never enlarged (D: a smaller picture stays at its size). */
    val fitScale: Float get() = minOf(1f, screenWidth.toFloat() / shownWidth.coerceAtLeast(1), screenHeight.toFloat() / shownHeight.coerceAtLeast(1))
}

/** The zoom [level] (0: fitted) and the turned picture's point at the screen's centre. */
data class Zoom(val level: Int, val x: Float, val y: Float) {
    fun scale(f: Frame): Float = f.fitScale * ZOOM_STEP.pow(level)

    fun zoomIn(f: Frame): Zoom = if (level >= MAX_LEVEL) this else copy(level = level + 1).inside(f)

    /** Escape: one level out; fitted already, null (the viewer closes). */
    fun zoomOut(f: Frame): Zoom? = if (level == 0) null else copy(level = level - 1).inside(f)

    /** [dx], [dy] steps of the trackpad (or of 2, 4, 6, 8). */
    fun pan(dx: Int, dy: Int, f: Frame): Zoom = drag(dx * PAN_STEP.toFloat(), dy * PAN_STEP.toFloat(), f)

    /** Moved by [dx], [dy] screen pixels (a finger's drag, D). */
    fun drag(dx: Float, dy: Float, f: Frame): Zoom {
        val s = scale(f)
        return copy(x = x + dx / s, y = y + dy / s).inside(f)
    }

    /** The picture's edges no further in than the screen's; smaller than the screen, centred. */
    private fun inside(f: Frame): Zoom {
        val s = scale(f)
        return copy(x = clamp(x, f.screenWidth / 2f / s, f.shownWidth.toFloat()), y = clamp(y, f.screenHeight / 2f / s, f.shownHeight.toFloat()))
    }

    // Within a pixel of the screen counts as fitting: the float's error never inverts the bounds.
    private fun clamp(v: Float, half: Float, size: Float) = if (2 * half >= size - 1f) size / 2 else v.coerceIn(half, size - half)

    companion object {
        fun fit(f: Frame) = Zoom(0, f.shownWidth / 2f, f.shownHeight / 2f)
    }
}

/** What the viewer shows: picture [index] of [count], [quarter] turns, zoomed or not. */
data class Viewing(val index: Int, val count: Int, val quarter: Int = 0, val level: Int = 0) {
    /** Fitted, the trackpad's left and right go to the previous and the next picture, and stop at the ends (D). */
    fun step(forward: Boolean): Viewing {
        if (level > 0 || count == 0) return this
        val next = (index + if (forward) 1 else -1).coerceIn(0, count - 1)
        return if (next == index) this else Viewing(next, count)
    }

    /** Rotate (guide 7.1 p. 153, B; L, p. 55): a quarter turn clockwise (D for the direction), fitted again. */
    fun rotate(): Viewing = copy(quarter = (quarter + 1) % 4, level = 0)

    /** "2 of 14" under the category's name (sim-364, C). */
    val position: String get() = "${index + 1} of $count"
}
