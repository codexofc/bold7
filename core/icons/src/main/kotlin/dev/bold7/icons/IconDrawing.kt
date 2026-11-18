package dev.bold7.icons

// Draws the chain of Tile.kt past the table, on a Canvas: the Android edge of this library,
// used by the launcher (grid, folders, search, switcher). Decides nothing: every choice and every
// pixel of light is Tile.kt's.

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max

/** Size at which a legacy icon is measured: fine enough for its visible box. */
private const val PROBE = 192

/** The theme's shortcut template (Tile.kt), decoded once by the caller from the drawables it ships. */
class IconTemplate(val underlay: Bitmap, val mask: Bitmap, val overlay: Bitmap)

/** An icon the chain drew past the table, with the step that drew it (for the logs). */
class DrawnIcon(val bitmap: Bitmap, val stage: IconStage)

/**
 * [icon] as a BBOS 7 icon of [cell] px, in the app's own colours lit by the theme (glaze): an
 * adaptive icon's two layers, or a square legacy icon, fill the template (underlay, mask, rim);
 * a legacy icon of another shape keeps it, over its shadow. A few ms of drawing: never on the
 * main thread. [icon] is mutated, its own instance only. The monochrome layer is never used: a
 * white glyph on the dark underlay is the black tile OS 7 never showed.
 *
 * Every drawable is drawn at its natural size (its intrinsic one: 108 dp for a layer) and scaled
 * by the canvas, so an inset in dp scales with the rest.
 */
fun drawBbos7Icon(icon: Drawable, cell: Int, template: IconTemplate): DrawnIcon {
    val g = TileGeometry(cell)
    val d = icon.mutate()
    val adaptive = d as? AdaptiveIconDrawable
    val content = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(content)
    if (adaptive != null) {
        for (layer in listOfNotNull(adaptive.background, adaptive.foreground)) canvas.drawScaled(layer, g.layer)
        return DrawnIcon(compose(content, cell, g, template), IconStage.RECOMPOSED)
    }
    val alpha = renderAlpha(PROBE, Box(0, 0, PROBE, PROBE), d)
    val square = fillsSquare(alpha, PROBE, PROBE)
    legacyBounds(alpha, PROBE, PROBE, g)?.let { canvas.drawScaled(d, it) }
    val stage = iconStage(inTable = false, adaptive = false, square = square)
    return DrawnIcon(if (square) compose(content, cell, g, template) else shape(content, cell), stage)
}

/** The bare template: what an icon that fails to draw shows instead. */
fun neutralTile(cell: Int, template: IconTemplate): Bitmap =
    compose(Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888), cell, TileGeometry(cell), template)

/**
 * Underlay, then [content] cut by the mask and glazed over the mask's rows, then the rim, each
 * stretched to [cell] (92 px: the theme's pixels). [content] is recycled.
 */
private fun compose(content: Bitmap, cell: Int, g: TileGeometry, template: IconTemplate): Bitmap {
    val whole = Rect(0, 0, cell, cell)
    val cut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN); isFilterBitmap = true }
    Canvas(content).drawBitmap(template.mask, null, whole, cut)
    glazed(content, g.mask.top, g.mask.bottom)
    val out = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(template.underlay, null, whole, paint)
    canvas.drawBitmap(content, 0f, 0f, null)
    canvas.drawBitmap(template.overlay, null, whole, paint)
    content.recycle()
    return out
}

/** A free shape: its shadow, then itself glazed over its own rows. [content] is recycled. */
private fun shape(content: Bitmap, cell: Int): Bitmap {
    val px = IntArray(cell * cell)
    content.getPixels(px, 0, cell, 0, 0, cell, cell)
    val alpha = IntArray(px.size) { px[it] ushr 24 }
    val shadow = Bitmap.createBitmap(dropShadow(alpha, cell, cell).map { it shl 24 }.toIntArray(), cell, cell, Bitmap.Config.ARGB_8888)
    visibleBox(alpha, cell, cell)?.let { glazed(content, it.top, it.bottom) }
    val out = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(shadow, 0f, 0f, null)
    canvas.drawBitmap(content, 0f, 0f, null)
    shadow.recycle()
    content.recycle()
    return out
}

/** [bitmap]'s pixels through [glaze], rows [top] to [bottom] lit. */
private fun glazed(bitmap: Bitmap, top: Int, bottom: Int) {
    val w = bitmap.width; val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    glaze(px, w, top, bottom)
    bitmap.setPixels(px, 0, w, 0, 0, w, h)
}

/** [d] at its natural bounds (its intrinsic size, square), scaled by the canvas onto [target]. */
// Simplification: a non-square legacy icon is stretched square; launcher icons are square.
private fun Canvas.drawScaled(d: Drawable, target: Box) {
    val natural = max(d.intrinsicWidth, d.intrinsicHeight).takeIf { it > 0 } ?: PROBE
    d.setBounds(0, 0, natural, natural)
    val count = save()
    translate(target.left.toFloat(), target.top.toFloat())
    scale(target.width.toFloat() / natural, target.height.toFloat() / natural)
    d.draw(this)
    restoreToCount(count)
}

/** Alpha (0 to 255) of each pixel of [drawable] drawn onto [target] of a [size] px square, one array. */
private fun renderAlpha(size: Int, target: Box, drawable: Drawable): IntArray {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawScaled(drawable, target)
    val px = IntArray(size * size)
    bitmap.getPixels(px, 0, size, 0, 0, size, size)
    bitmap.recycle()
    for (i in px.indices) px[i] = px[i] ushr 24
    return px
}

/**
 * A third-party app's indicator (Tile.kt, [INDICATOR_BOX]): its BBOS 7 [icon] (the grid's, from
 * the table or drawn, [TEMPLATE_SIZE] px) without its margin, brought down to 29 px in steps
 * ([downscaleSteps]) in a 33x29 frame. Never on the main thread.
 */
fun indicatorBitmap(icon: Bitmap): Bitmap {
    val small = marginless(icon, INDICATOR_SIDE)
    // Drawn at their pixels, never scaled by a density (the theme's images are DENSITY_NONE too).
    val frame = Bitmap.createBitmap(INDICATOR_FRAME_WIDTH, INDICATOR_FRAME_HEIGHT, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
    Canvas(frame).drawBitmap(small, INDICATOR_BOX.left.toFloat(), INDICATOR_BOX.top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
    return frame
}

/**
 * [icon] (the grid's, [TEMPLATE_SIZE] px) without its [INDICATOR_MARGIN] of margin and shadow,
 * brought down to [side] px in steps ([downscaleSteps]): an indicator's, or a list row's status
 * icon (Messages, 42 px). Never on the main thread.
 */
fun marginless(icon: Bitmap, side: Int): Bitmap {
    val m = icon.width * INDICATOR_MARGIN / TEMPLATE_SIZE
    val crop = Bitmap.createBitmap(icon, m, m, icon.width - 2 * m, icon.height - 2 * m)
    return downscaleSteps(crop.width, side).fold(crop) { b, size -> Bitmap.createScaledBitmap(b, size, size, true) }.apply { density = Bitmap.DENSITY_NONE }
}
