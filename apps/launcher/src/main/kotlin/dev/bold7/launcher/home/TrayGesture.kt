package dev.bold7.launcher.home

import dev.bold7.design.HomeDimens
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

// The tray under the finger, pure: no android.*, tested on the JVM. The 9900's screen was a
// touch screen: its tray rose, turned and scrolled under the finger. What each move follows and
// where it rests is our own (D); HomeView applies it, every release ending on spring().

/** Release speed, px/s, from which the direction of a flick decides, whatever the distance (by eye). */
const val FLICK = 650f

enum class Axis { SIDEWAYS, UPDOWN }

/** The axis a drag of ([dx], [dy]) takes once past [slop], or null before. */
fun axis(dx: Float, dy: Float, slop: Float): Axis? = when {
    abs(dx) <= slop && abs(dy) <= slop -> null
    abs(dx) > abs(dy) -> Axis.SIDEWAYS
    else -> Axis.UPDOWN
}

/** How far up the tray is, from [start] (0 lowered, 1 raised), the finger [dy] px down over a [travel] px rise. */
fun raised(start: Float, dy: Float, travel: Float): Float = (start - dy / travel).coerceIn(0f, 1f)

/** Whether the tray released at [fraction] up, at [velocity] px/s (negative up), rests raised. */
fun settlesRaised(fraction: Float, velocity: Float): Boolean = if (abs(velocity) >= FLICK) velocity < 0 else fraction >= 0.5f

/**
 * The view a sideways drag of [dx] released at [velocity] turns to: 1 the next (dragged left),
 * -1 the previous, 0 its own; a sixth of the [width], or a flick, a flick back cancels.
 */
fun panelStep(dx: Float, velocity: Float, width: Float): Int = when {
    abs(velocity) >= FLICK -> if (sign(velocity) == sign(dx)) -sign(velocity).toInt() else 0
    abs(dx) >= width / 6 -> -sign(dx).toInt()
    else -> 0
}

private fun maxOffset(rows: Int, shown: Int) = (rows - shown).coerceAtLeast(0) * HomeDimens.ROW.toFloat()

/** The grid's offset (px, negative scrolled down) from [start] the finger [dy] px down, within its [rows]. */
fun scrolled(start: Float, dy: Float, rows: Int, shown: Int): Float = (start + dy).coerceIn(-maxOffset(rows, shown), 0f)

/** The first row the grid rests on from [offset], released at [velocity] (a flick goes on a row). */
fun restRow(offset: Float, velocity: Float, rows: Int, shown: Int): Int {
    val at = -offset / HomeDimens.ROW
    val row = when {
        velocity <= -FLICK -> ceil(at).toInt()
        velocity >= FLICK -> floor(at).toInt()
        else -> at.roundToInt()
    }
    return row.coerceIn(0, (rows - shown).coerceAtLeast(0))
}

/** The tile under ([x], [y]) of the grid's shown area, [firstRow] scrolled away, within its [count] tiles. */
fun cellUnder(x: Float, y: Float, firstRow: Int, count: Int): Int {
    val column = (x / HomeDimens.COLUMN).toInt().coerceIn(0, HomeDimens.COLUMNS - 1)
    val row = (y / HomeDimens.ROW).toInt().coerceAtLeast(0) + firstRow
    return (row * HomeDimens.COLUMNS + column).coerceIn(0, (count - 1).coerceAtLeast(0))
}

/** Within this distance of the grid's top or bottom, a moved icon scrolls it a row at a time (by eye). */
const val EDGE = 40

/** -1 scrolls up, 1 down, 0 not: a moved icon at [y] of the grid's [height] px shown. */
fun edgeScroll(y: Float, height: Float): Int = when {
    y < EDGE -> -1
    y > height - EDGE -> 1
    else -> 0
}

sealed interface Drop {
    /** The moved icon takes place [index], the others make room. */
    data class At(val index: Int) : Drop

    /** The moved app goes into the folder at [index]. */
    data class IntoFolder(val index: Int) : Drop
}

/**
 * Where the icon at [from] goes, held over [cell] of [tiles], over its icon ([inIcon]) or not: an
 * app held over a folder's icon goes into it (our choice, declared in BBOS7-VISUEL §3), anything
 * else takes the place.
 */
fun dropAt(tiles: List<Tile>, from: Int, cell: Int, inIcon: Boolean): Drop =
    if (inIcon && cell != from && tiles.getOrNull(cell) is Folder && tiles.getOrNull(from) is App) Drop.IntoFolder(cell) else Drop.At(cell)
