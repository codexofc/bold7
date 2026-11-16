package dev.bold7.contacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint

/**
 * A row of the contact list (sim-178, C): 72 px and a 1 px line, the contact's picture 67x67 at
 * (3, 3) with rounded corners (by eye on gsm 013), or a theme image drawn as it is ([framed]: the
 * default picture, addressbook_contact.png, whose drawing starts 6 px in), the name in BBAlpha Sans
 * 27 black at x 79 on a baseline 31; New Contact's picture with its note "Create a new contact" in
 * 23 grey on 63. Under the trackpad's focus the blue band over the whole row,
 * white texts (C).
 */
class PersonRowView(context: Context) : View(context) {
    companion object {
        const val HEIGHT = 73
        const val PICTURE = 67
        const val PICTURE_X = 3
        const val PICTURE_Y = 3
        /** The corners of the picture, by eye on gsm 013. */
        const val CORNER = 10f
        /** addressbook_contact.png's margin around its drawing (A). */
        const val FRAME = 6
        /** New Contact's note, 63 px under the row's top (sim-178, C; Options' is 60). */
        const val NOTE_BASELINE = 63
    }

    private val divider = fillPaint(ListColors.Divider)
    private val focus = focusPaint(HEIGHT)
    private val name = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val note = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Subtitle)
    private val lit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    private val litNote = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText)
    private val picturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val rect = RectF(PICTURE_X.toFloat(), PICTURE_Y.toFloat(), (PICTURE_X + PICTURE).toFloat(), (PICTURE_Y + PICTURE).toFloat())
    private var title = ""
    private var subtitle = ""
    /** [title] and [subtitle] cut to the row's room ("…"), worked out when they or the width change, never in onDraw. */
    private var shownTitle = ""
    private var shownSubtitle = ""
    private var focused = false
    private var picture: Bitmap? = null
    private var framed: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEIGHT)
    }

    fun bind(title: String, subtitle: String, focused: Boolean, picture: Bitmap?, framed: Bitmap? = null) {
        if (title == this.title && subtitle == this.subtitle && focused == this.focused && picture === this.picture && framed === this.framed) return
        this.framed = framed
        this.title = title
        this.subtitle = subtitle
        this.focused = focused
        refit()
        if (picture !== this.picture) {
            this.picture = picture
            picturePaint.shader = picture?.let {
                // The picture fills its 67 px square, scaled down from Android's thumbnail (96 px).
                val s = PICTURE.toFloat() / minOf(it.width, it.height)
                matrix.setScale(s, s)
                matrix.postTranslate(PICTURE_X - (it.width * s - PICTURE) / 2, PICTURE_Y - (it.height * s - PICTURE) / 2)
                BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            }
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refit()

    private fun refit() {
        val room = ContactRoom.listRow(width)
        // Bold or not, the lit texts are the same size: the plain paints measure for both.
        shownTitle = if (width == 0) title else name.fit(title, room)
        shownSubtitle = if (width == 0) subtitle else note.fit(subtitle, room)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val line = (HEIGHT - ListDimens.DIVIDER).toFloat()
        if (focused) canvas.drawRect(0f, 0f, w, HEIGHT.toFloat(), focus) else canvas.drawRect(0f, line, w, HEIGHT.toFloat(), divider)
        if (picture != null) canvas.drawRoundRect(rect, CORNER, CORNER, picturePaint)
        else framed?.let { canvas.drawBitmap(it, (PICTURE_X - FRAME).toFloat(), (PICTURE_Y - FRAME).toFloat(), bitmapPaint) }
        canvas.drawText(shownTitle, ListDimens.ICON_TEXT_X.toFloat(), ListDimens.TITLE_BASELINE.toFloat(), if (focused) lit else name)
        if (shownSubtitle.isNotEmpty()) canvas.drawText(shownSubtitle, ListDimens.ICON_TEXT_X.toFloat(), NOTE_BASELINE.toFloat(), if (focused) litNote else note)
    }
}
