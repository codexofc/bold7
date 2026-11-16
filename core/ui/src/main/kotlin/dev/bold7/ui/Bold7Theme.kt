package dev.bold7.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.widget.AbsListView
import android.widget.ListView
import dev.bold7.design.Focus
import dev.bold7.design.TextToken
import dev.bold7.design.Typography
import dev.bold7.theme.ThemeImages

// The view helpers every component draws with, over the tokens of core/design (Bold7Theme). Sizes are px of the 9900, the Q25's own (docs/BBOS7-VISUEL.md).

/** The system's font at [weight] (BBAlpha Sans: 400 and 700), [family] sans-serif or its condensed. */
fun slate(weight: Int, italic: Boolean = false, family: String = Typography.FAMILY): Typeface =
    Typeface.create(Typeface.create(family, Typeface.NORMAL), weight, italic)

fun TextToken.typeface(): Typeface = slate(weight, family = family)

/** An antialiased text paint: BBAlpha Sans [weight], [size] px, [color]. */
fun textPaint(size: Int, weight: Int, color: Int, italic: Boolean = false, align: Paint.Align = Paint.Align.LEFT, family: String = Typography.FAMILY) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size.toFloat()
        typeface = slate(weight, italic, family)
        this.color = color
        textAlign = align
    }

/** A plain fill of [color], for the rectangles of the bars, rows and lines. */
fun fillPaint(color: Int) = Paint().apply { this.color = color }

/**
 * The blue of a focused row or header, [height] px from its top (sim-04, sim-57, sim-53, C):
 * drawn with its top at y = 0 of the canvas, which the view translates to the row.
 */
fun focusPaint(height: Int, top: Int = Focus.RowTop, bottom: Int = Focus.RowBottom) =
    Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP) }

/**
 * A theme image of core/ui (tools/import-assets.sh ui), a 9-patch where the firmware stretches it.
 * Simplification: decoded on the main thread when a component is made, as Android's own widgets do their
 * backgrounds: a dozen images of 1 to 10 KB, kept by Resources' cache for the process. Moves to an
 * executor if a measure shows the first menu or dialog late.
 */
fun themeImage(context: Context, id: Int): Drawable = requireNotNull(context.getDrawable(id)) { "theme image $id" }

/**
 * A theme image by its name: from the folder the module mounts, else the APK's
 * resource of that name ([ThemeImages]). Prefer it to [themeImage] by id.
 */
fun themeImage(context: Context, name: String): Drawable = ThemeImages.of(context).drawable(name)

/** What a [MenuView] opens when it is made, for an app to preload with its own. */
val MENU_IMAGES = listOf("ui_choice_list", "ui_menu", "ui_sm_full_menu", "ui_menu_arrow")

/** The shared bitmap of a theme image by its name (sprites, icons): never to be changed. */
fun themeBitmap(context: Context, name: String): Bitmap = ThemeImages.of(context).bitmap(name)

/**
 * A ListView as OS 7's lists: no divider of Android (each row draws its line), no scroll bar of
 * Android, no stretch at the ends, the row under the finger in [pressed] (the focus is the rows'
 * own, drawn by them).
 */
fun bb10List(list: ListView, pressed: Int) = list.apply {
    divider = null
    dividerHeight = 0
    selector = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressed))
        addState(intArrayOf(), ColorDrawable(0))
    }
    isVerticalScrollBarEnabled = false
    overScrollMode = AbsListView.OVER_SCROLL_NEVER
}
