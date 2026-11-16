package dev.bold7.icons

// Draws the chain of Tile.kt past the table, on a Canvas: the Android edge of this library,
// used by the launcher (grid, folders, search, frames). Decides
// nothing: every choice is made by Tile.kt.

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max

/** Size at which a glyph or a legacy icon is measured: fine enough for its visible box. */
private const val PROBE = 192

/** The theme's shortcut template (Tile.kt), decoded once by the caller from the drawables it ships. */
class IconTemplate(val underlay: Bitmap, val mask: Bitmap, val overlay: Bitmap)

/** An icon the chain drew past the table, with the step that drew it (for the logs). */
class DrawnIcon(val bitmap: Bitmap, val stage: IconStage)

/**
 * [icon] as a BBOS 7 icon of [cell] px: the underlay, the app's image clipped by the mask (a glyph
 * in white, the adaptive icon recomposed, a legacy icon, Tile.kt), the rim. A few ms of drawing:
 * never on the main thread. [icon] is mutated, its own instance only.
 *
 * Every drawable is drawn at its natural size (its intrinsic one: 108 dp for a layer) and scaled
 * by the canvas: an inset in dp (Drive's monochrome layer: 26 dp each side of 108) then scales
 * with the rest.
 */
fun drawBbos7Icon(icon: Drawable, cell: Int, template: IconTemplate): DrawnIcon {
    val g = TileGeometry(cell)
    val d = icon.mutate()
    val all = Box(0, 0, PROBE, PROBE)
    val adaptive = d as? AdaptiveIconDrawable
    val mono = adaptive?.monochrome
    val glyph = mono?.let { glyphBox(renderAlpha(PROBE, all, it), PROBE, PROBE) }
    val stage = iconStage(inTable = false, adaptive = adaptive != null, monochrome = glyph != null)
    val content = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(content)
    when (stage) {
        IconStage.TILE -> {
            mono!!.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            canvas.drawScaled(mono, fit(glyph!!, PROBE, g.glyph, g))
        }
        IconStage.RECOMPOSED -> for (layer in listOfNotNull(adaptive!!.background, adaptive.foreground)) canvas.drawScaled(layer, g.layer)
        else -> legacyBounds(renderAlpha(PROBE, all, d), PROBE, PROBE, g)?.let { canvas.drawScaled(d, it) }
    }
    return DrawnIcon(compose(content, cell, template), stage)
}

/** The bare template: what an icon that fails to draw shows instead. */
fun neutralTile(cell: Int, template: IconTemplate): Bitmap = compose(Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888), cell, template)

/** Underlay, then [content] cut by the mask, then the rim, each stretched to [cell] (92 px: the theme's pixels). [content] is recycled. */
private fun compose(content: Bitmap, cell: Int, template: IconTemplate): Bitmap {
    val whole = Rect(0, 0, cell, cell)
    val cut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN); isFilterBitmap = true }
    Canvas(content).drawBitmap(template.mask, null, whole, cut)
    val out = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(template.underlay, null, whole, paint)
    canvas.drawBitmap(content, 0f, 0f, null)
    canvas.drawBitmap(template.overlay, null, whole, paint)
    content.recycle()
    return out
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
