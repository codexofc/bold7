package dev.bold7.icons

// Bold7's generated app icon, for the apps the table has no BBOS 7 icon for: what to draw and
// where, pure, no android.*, tested on the JVM. IconDrawing.kt draws it.
//
// The rule is the theme's own shortcut template, the icon BBOS 7 makes for a web page added to the
// home screen and for an app without an icon of its own: shortcut_underlay.png (a dark squircle
// with its drop shadow), the app's image clipped by shortcut_mask.png, then shortcut_overlay.png
// (a white rim), all three 92x92 in net_rim_theme_BlackBerry7_640x480_b (A). The simulator's
// Facebook and Twitter icons are exactly that: a full-bleed image in the rimmed squircle (C,
// sim-06). What the theme does not say, how large an image sits in the mask, is eyeballed.

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which step of the chain draws an app's icon, the first that can answer wins. */
enum class IconStage {
    /** The table's BBOS 7 icon, copied from the firmware's theme. */
    TABLE,

    /** The adaptive icon's monochrome layer, in white, on the underlay. */
    TILE,

    /** The adaptive icon's two layers filling the mask, as Facebook's icon does on the 9900. */
    RECOMPOSED,

    /** A non-adaptive icon: filling the mask if it is a square already, else set on the underlay. */
    LEGACY,
}

fun iconStage(inTable: Boolean, adaptive: Boolean, monochrome: Boolean): IconStage = when {
    inTable -> IconStage.TABLE
    adaptive && monochrome -> IconStage.TILE
    adaptive -> IconStage.RECOMPOSED
    else -> IconStage.LEGACY
}

/** The three images of the template, as drawable names: tools/import-assets.sh copies them from the theme. */
const val TEMPLATE_UNDERLAY = "bbos7_shortcut_underlay"
const val TEMPLATE_MASK = "bbos7_shortcut_mask"
const val TEMPLATE_OVERLAY = "bbos7_shortcut_overlay"

/** Pixel rectangle, [right] and [bottom] exclusive. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** The template's images are 92x92, as every icon of the grid (A). */
const val TEMPLATE_SIZE = 92

/** The mask's opaque squircle, measured on shortcut_mask.png: 62x62 at (15, 15) of 92 (A). */
private const val MASK_LEFT = 15
private const val MASK_SIDE = 62

/** Eyeballed: a glyph's longer side over the mask's. */
private const val GLYPH = 0.6f

/** Eyeballed: a legacy icon set on the underlay keeps a margin, its longer side at 75 % of the mask. */
private const val LEGACY = 0.75f

/**
 * The template in a [cell] px icon: at 92 px the theme's own pixels, at any other size the same
 * boxes scaled. The [mask] box, its [centre], the [glyph] size, the size of a [legacy] icon set on
 * it, and where an adaptive [layer] goes so its visible 72 dp fill the mask.
 */
class TileGeometry(cell: Int) {
    private val k = cell / TEMPLATE_SIZE.toFloat()
    private val side = (MASK_SIDE * k).roundToInt()
    private val left = (MASK_LEFT * k).roundToInt()
    val mask = Box(left, left, left + side, left + side)
    val centre = left + side / 2f
    val glyph = (side * GLYPH).roundToInt()
    val legacy = (side * LEGACY).roundToInt()

    /** 108 dp of layer, 72 visible: a sixth of the layer, a quarter of the mask, beyond each edge. */
    val layer: Box = (side / 4f).roundToInt().let { m -> Box(mask.left - m, mask.top - m, mask.right + m, mask.bottom + m) }
}

/** The box of the pixels of [alpha] (0 to 255, row by row, [width] wide) that are not transparent; null if none. */
fun visibleBox(alpha: IntArray, width: Int, height: Int): Box? {
    var l = width; var t = height; var r = -1; var b = -1
    for (y in 0 until height) for (x in 0 until width) {
        if (alpha[y * width + x] == 0) continue
        l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y)
    }
    return if (r < 0) null else Box(l, t, r + 1, b + 1)
}

/**
 * Where to draw a [probe] px square drawable so that its [visible] pixels (measured at that
 * size) have their longer side at [target] px, centred in [g]'s mask.
 */
fun fit(visible: Box, probe: Int, target: Int, g: TileGeometry): Box {
    val size = (probe * target.toFloat() / max(visible.width, visible.height)).roundToInt()
    val k = size.toFloat() / probe
    val left = (g.centre - (visible.left + visible.right) / 2f * k).roundToInt()
    val top = (g.centre - (visible.top + visible.bottom) / 2f * k).roundToInt()
    return Box(left, top, left + size, top + size)
}

/** Share of [box] that [alpha] covers opaquely (128 and over). */
private fun coverage(alpha: IntArray, width: Int, box: Box): Float {
    var opaque = 0
    for (y in box.top until box.bottom) for (x in box.left until box.right) if (alpha[y * width + x] >= 128) opaque++
    return opaque.toFloat() / (box.width * box.height)
}

/** True if a legacy icon's opaque pixels fill a square of their own (a tile already, not a shape). */
fun fillsSquare(alpha: IntArray, width: Int, height: Int): Boolean {
    val box = visibleBox(alpha, width, height) ?: return false
    if (abs(box.width - box.height) > max(box.width, box.height) / 10) return false
    return coverage(alpha, width, box) >= 0.85f
}

/**
 * The visible box of a monochrome layer measured at [width] x [height], or null when it has no
 * glyph: empty, or solid over more than 70 % of its box, a disc or a square (the layer repeats the
 * foreground's silhouette, as Android Studio's template does), which would be a white blot.
 */
fun glyphBox(alpha: IntArray, width: Int, height: Int): Box? =
    visibleBox(alpha, width, height)?.takeIf { coverage(alpha, width, it) <= 0.7f }

/**
 * Where a legacy icon measured on a [width] px square goes: filling the mask when it already is
 * one, else its longer side at [TileGeometry.legacy]; null when it shows nothing.
 */
fun legacyBounds(alpha: IntArray, width: Int, height: Int, g: TileGeometry): Box? =
    visibleBox(alpha, width, height)?.let { fit(it, width, if (fillsSquare(alpha, width, height)) g.mask.width else g.legacy, g) }
