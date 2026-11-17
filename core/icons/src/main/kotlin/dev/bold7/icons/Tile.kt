package dev.bold7.icons

// Bold7's generated app icon, for the apps the table has no BBOS 7 icon for: what to draw, where
// and in which light, pure, no android.*, tested on the JVM. IconDrawing.kt draws it.
//
// A third-party app on the 9900's grid wore its own icon, in colour, lit from the top: Facebook's
// home screen icon (homescreen_icon.png, 92x92, net_rim_bb_facebook_resource_high_res, A; C
// sim-06) is a 64 px rounded square with a chrome rim, a white sheen over its upper half and a
// drop shadow. That is the theme's shortcut template (shortcut_underlay.png: dark squircle and
// drop shadow; shortcut_mask.png; shortcut_overlay.png: the chrome rim; 92x92 in
// net_rim_theme_BlackBerry7_640x480_b, A), the icon BBOS 7 makes for a page added to the home
// screen. Bold7 fills it with the app's own icon, in its own colours, and lays the theme's light
// over it (glaze). Never a white glyph on the dark underlay: that was the old chain's TILE step,
// a black tile, nothing like OS 7. A legacy icon that is not a square keeps its own shape, as the
// firmware's third-party icons do (Wikitude, Caller ID: free shapes, A), with the underlay's
// shadow under it.

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which step of the chain draws an app's icon, the first that can answer wins. */
enum class IconStage {
    /** The table's BBOS 7 icon, copied from the firmware's theme. */
    TABLE,

    /** The adaptive icon's two layers, in colour, filling the mask, as Facebook's icon does on the 9900. */
    RECOMPOSED,

    /** A non-adaptive icon that already is a square: filling the mask the same way. */
    SQUARE,

    /** A non-adaptive icon of any other shape: that shape alone, lit, over its own shadow. */
    SHAPE,
}

fun iconStage(inTable: Boolean, adaptive: Boolean, square: Boolean): IconStage = when {
    inTable -> IconStage.TABLE
    adaptive -> IconStage.RECOMPOSED
    square -> IconStage.SQUARE
    else -> IconStage.SHAPE
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

/** The rim's box, shortcut_overlay.png, and Facebook's tile: 64 px (A). A free shape gets as much. */
private const val RIM_SIDE = 64

/**
 * The template in a [cell] px icon: at 92 px the theme's own pixels, at any other size the same
 * boxes scaled. The [mask] box, its [centre], the size of a free [shape], and where an adaptive
 * [layer] goes so its visible 72 dp fill the mask.
 */
class TileGeometry(cell: Int) {
    private val k = cell / TEMPLATE_SIZE.toFloat()
    private val side = (MASK_SIDE * k).roundToInt()
    private val left = (MASK_LEFT * k).roundToInt()
    val mask = Box(left, left, left + side, left + side)
    val centre = left + side / 2f
    val shape = (RIM_SIDE * k).roundToInt()

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
 * Where a legacy icon measured on a [width] px square goes: filling the mask when it already is
 * one, else its longer side at [TileGeometry.shape]; null when it shows nothing.
 */
fun legacyBounds(alpha: IntArray, width: Int, height: Int, g: TileGeometry): Box? =
    visibleBox(alpha, width, height)?.let { fit(it, width, if (fillsSquare(alpha, width, height)) g.mask.width else g.shape, g) }

/**
 * The white laid over a row of the icon at [f] (0 its top, 1 its bottom): Facebook's home screen
 * icon (A), whose blue goes a third of the way to white at the top, 29 % at an eighth of its
 * height, 23 % at a fifth, 15 % at 28 %, 8 % at 36 %, and nothing from just under the middle.
 */
fun gloss(f: Float): Float = when {
    f <= 0.08f -> 0.33f
    f >= 0.46f -> 0f
    else -> 0.33f * (0.46f - f) / 0.38f
}

/** The darkening of a row at [f]: YouTube's white tile (A) goes evenly from 254 at its top to 219 at its bottom. */
fun shade(f: Float): Float = 1f - 0.14f * f.coerceIn(0f, 1f)

private fun red(c: Int) = c shr 16 and 0xFF
private fun green(c: Int) = c shr 8 and 0xFF
private fun blue(c: Int) = c and 0xFF

/**
 * True when the opaque pixels of [px] (ARGB) are grey and mostly dark: a black tile with a white
 * glyph, Android's flat monochrome look, which OS 7 never shows for an app (D).
 */
fun darkAndGrey(px: IntArray): Boolean {
    var n = 0; var chroma = 0L; var light = 0L
    for (c in px) {
        if (c ushr 24 < 128) continue
        val r = red(c); val g = green(c); val b = blue(c)
        n++
        chroma += max(r, max(g, b)) - min(r, min(g, b))
        light += (299 * r + 587 * g + 114 * b) / 1000
    }
    return n > 0 && chroma < 26L * n && light < 128L * n
}

/**
 * [px] (ARGB, not premultiplied, row by row, [width] wide) lit like the theme's icons, in place:
 * each row from [top] to [bottom] (the icon's own box) whitened by [gloss], then darkened by
 * [shade]; the app's hues stay. A grey and dark image ([darkAndGrey]) is turned over first, its
 * black ground white and its white glyph black, as YouTube's tile (D). Alpha never changes.
 */
fun glaze(px: IntArray, width: Int, top: Int, bottom: Int) {
    val turn = darkAndGrey(px)
    val h = (bottom - top).toFloat()
    for (i in px.indices) {
        val a = px[i] ushr 24
        if (a == 0) continue
        val f = (i / width - top + 0.5f) / h
        val w = gloss(f); val s = shade(f)
        fun lit(v: Int): Int { val c = if (turn) 255 - v else v; return ((c + (255 - c) * w) * s).roundToInt().coerceIn(0, 255) }
        val c = px[i]
        px[i] = (a shl 24) or (lit(red(c)) shl 16) or (lit(green(c)) shl 8) or lit(blue(c))
    }
}

/**
 * The shadow under a free shape of [alpha] ([width] x [height], 0 to 255): the shape blurred over
 * [SHADOW_RADIUS] px, moved [SHADOW_DROP] px down, at [SHADOW_OPACITY]. The underlay's own shadow
 * (A): half opaque just under its squircle, gone 13 px below, faint on the sides and the top.
 */
fun dropShadow(alpha: IntArray, width: Int, height: Int): IntArray {
    val moved = IntArray(width * height) { i -> val y = i / width - SHADOW_DROP; if (y < 0) 0 else alpha[y * width + i % width] }
    val blurred = boxBlur(boxBlur(moved, width, height, horizontal = true), width, height, horizontal = false)
    return IntArray(blurred.size) { (blurred[it] * SHADOW_OPACITY).roundToInt() }
}

private const val SHADOW_RADIUS = 3
private const val SHADOW_DROP = 3
private const val SHADOW_OPACITY = 0.5f

/** One pass of a box blur of [SHADOW_RADIUS] along rows or columns, edges counted as transparent. */
private fun boxBlur(a: IntArray, width: Int, height: Int, horizontal: Boolean): IntArray {
    val out = IntArray(a.size)
    val n = 2 * SHADOW_RADIUS + 1
    for (y in 0 until height) for (x in 0 until width) {
        var sum = 0
        for (d in -SHADOW_RADIUS..SHADOW_RADIUS) {
            val xx = if (horizontal) x + d else x
            val yy = if (horizontal) y else y + d
            if (xx in 0 until width && yy in 0 until height) sum += a[yy * width + xx]
        }
        out[y * width + x] = sum / n
    }
    return out
}

/**
 * A third-party app's indicator in the banners: its own small icon, as OS 7 showed it (the
 * firmware's third-party apps carry one of 25 to 27 px for the banner: App World's
 * indicator_25x25, Facebook's unread_icon_ribbon_25x22, Twitter's twitter_unread_small 27x27,
 * A). Bold7 draws the app's BBOS 7 icon as the list of notifications draws it in its header
 * (D): the 92 px icon without its [INDICATOR_MARGIN] px of margin and shadow,
 * [INDICATOR_SIDE] px square at [INDICATOR_BOX] of a counter's 33x29 frame
 * (net_rim_Ribbon_UnreadCount-icons33x29, A), so it takes a counter's place.
 */
const val INDICATOR_SIDE = 29
const val INDICATOR_MARGIN = 12
const val INDICATOR_FRAME_WIDTH = 33
const val INDICATOR_FRAME_HEIGHT = 29

/** Where the icon goes in its frame: (2, 0), the list's header's place. */
val INDICATOR_BOX = Box(2, 0, 2 + INDICATOR_SIDE, INDICATOR_SIDE)
