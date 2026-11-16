package dev.bold7.messages

// The picture viewer's zoom and pan, pure: "To zoom in to the picture, click the
// trackpad. To zoom out from the picture, press the Escape key. To pan the picture, when you are
// zoomed into it, slide your finger on the trackpad in any direction" (guide 7.1 p. 153, B);
// I and O zoom, 2, 4, 6, 8 pan (guide p. 38, B). The steps' sizes are mine (D).

/** The highest zoom: eight times the fitted picture (D). */
const val MAX_LEVEL = 3

/** A picture of [width] by [height] on a screen of [screenWidth] by [screenHeight]. */
data class Frame(val width: Int, val height: Int, val screenWidth: Int, val screenHeight: Int) {
    /** The whole picture in the screen, never enlarged (D). */
    val fitScale: Float get() = minOf(1f, screenWidth.toFloat() / width.coerceAtLeast(1), screenHeight.toFloat() / height.coerceAtLeast(1))
}

/** The zoom [level] (0: fitted, each one doubles) and the picture's point at the screen's centre. */
data class Zoom(val level: Int, val x: Float, val y: Float) {
    fun scale(f: Frame): Float = f.fitScale * (1 shl level)

    fun zoomIn(f: Frame): Zoom = if (level >= MAX_LEVEL) this else copy(level = level + 1).inside(f)

    /** Escape: one level out; fitted already, null (the viewer closes). */
    fun zoomOut(f: Frame): Zoom? = if (level == 0) null else copy(level = level - 1).inside(f)

    /** [dx], [dy] steps of an eighth of the screen each. */
    fun pan(dx: Int, dy: Int, f: Frame): Zoom = drag(dx * f.screenWidth / 8f, dy * f.screenHeight / 8f, f)

    /** Moved by [dx], [dy] screen pixels (a finger's drag, D). */
    fun drag(dx: Float, dy: Float, f: Frame): Zoom {
        val s = scale(f)
        return copy(x = x + dx / s, y = y + dy / s).inside(f)
    }

    /** The picture's edges no further in than the screen's; smaller than the screen, centred. */
    private fun inside(f: Frame): Zoom {
        val s = scale(f)
        return copy(x = clamp(x, f.screenWidth / 2f / s, f.width.toFloat()), y = clamp(y, f.screenHeight / 2f / s, f.height.toFloat()))
    }

    // Within a pixel of the screen counts as fitting: the float's error never inverts the bounds.
    private fun clamp(v: Float, half: Float, size: Float) = if (2 * half >= size - 1f) size / 2 else v.coerceIn(half, size - half)

    companion object {
        fun fit(f: Frame) = Zoom(0, f.width / 2f, f.height / 2f)
    }
}
