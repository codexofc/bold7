package dev.bold7.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography

/**
 * The search field at the top of an app's list, as Options' (sim-57, C; BBOS7-VISUEL §17): a band
 * of 60 px (the top of background_find_field.png, A) and in it the white field border_find.png (A,
 * 9-patch), the text in BBAlpha Sans 27 black at x 25, "Search" in #ADB0B3 while empty. An
 * [EditText]: the keyboard types into it, its owner reads [getText] (its text watcher).
 */
class SearchField(context: Context, hint: String) : EditText(context) {
    companion object {
        const val HEIGHT = ListDimens.SEARCH
    }

    init {
        background = Band(context)
        typeface = slate(Typography.REGULAR)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        setHintTextColor(ListColors.SearchHint)
        this.hint = hint
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(ListDimens.SEARCH_TEXT_X, 0, ListDimens.SEARCH_TEXT_X, 0)
        useCaret()
        minHeight = HEIGHT
        maxHeight = HEIGHT
    }

    /** The band and its field, as one background. */
    private class Band(context: Context) : Drawable() {
        private val band = themeImage(context, "ui_find_background")
        private val field = themeImage(context, "ui_find")

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            band.setBounds(bounds.left, bounds.top, bounds.right, bounds.top + HEIGHT)
            // border_find.png: 2 px of the band around its field already.
            val i = ListDimens.SEARCH_INSET
            field.setBounds(bounds.left + i, bounds.top + i, bounds.right - i, bounds.top + HEIGHT - i)
        }

        override fun draw(canvas: Canvas) {
            band.draw(canvas)
            field.draw(canvas)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
