package dev.bold7.saisie

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.DialogColors
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage

/**
 * The Sym grid (sim-303 to sim-305, C, [Symbols]): a popup of core/ui (border_popup.png, A) centred
 * on the screen, "Page 1 / 2" at its top right, three rows of bold glyphs each over the outline of
 * the keyboard's keys, the focused glyph in the blue of a focused row. Measured on the simulator's
 * 640 x 480 and scaled by [S] to the Q25's 720 px. As tall as the screen so that the input method's
 * window never resizes the app: only [panel] is drawn and touched ([SaisieService.onComputeInsets]).
 * A touch on a glyph chooses it ([onChoose]).
 */
@SuppressLint("ViewConstructor")
class SymbolView(context: Context, private val onChoose: (Symbols.Focus) -> Unit) : View(context) {
    var focus = Symbols.Focus(0, 0, 0)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The popup's box, in this view. */
    val panel = Rect()

    private val popup = themeImage(context, "ui_popup")
    private val glyphPaint = textPaint(34, Typography.BOLD, KEY_GRAY, align = Paint.Align.CENTER)
    private val glyphFocused = textPaint(34, Typography.BOLD, DialogColors.Text, align = Paint.Align.CENTER)
    private val keyPaint = textPaint(Typography.SUBTITLE, Typography.REGULAR, KEY_GRAY, align = Paint.Align.CENTER)
    private val titlePaint = textPaint(Typography.SUBTITLE, Typography.REGULAR, KEY_GRAY, align = Paint.Align.RIGHT)
    private val outline = Paint().apply { color = KEY_GRAY; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val focusFill = focusPaint(GLYPH_H)
    private val background = fillPaint(DialogColors.Background)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The whole height the window may take: the screen's.
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.heightPixels
        else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        panel.set((w - PANEL_W) / 2, (h - PANEL_H) / 2, (w + PANEL_W) / 2, (h + PANEL_H) / 2)
    }

    // Row r's keys: Q, A and Z start at 23, 39 and 57 simulator pixels; a key is 35.6 wide, SYM 57.
    private fun keyLeft(row: Int, col: Int) = panel.left + (KEY_X0[row] + col * KEY_W) * S
    private fun keyRight(row: Int, col: Int) = if (row == 2 && col == 7) keyLeft(row, col) + SYM_W * S else keyLeft(row, col) + KEY_W * S
    private fun keyTop(row: Int) = panel.top + KEY_Y0[row] * S
    private fun glyphTop(row: Int) = keyTop(row) - (KEY_Y0[row] - GLYPH_Y0[row]) * S

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(panel, background)
        // border_popup.png, 40x40: its border pixels are its column 4 and 35, its row 2 and 33 (as core/ui's DialogView).
        popup.setBounds(panel.left - 4, panel.top - 2, panel.right + 4, panel.bottom + 6)
        popup.draw(canvas)
        canvas.drawText("Page ${focus.page + 1} / 2", panel.left + TITLE_RIGHT * S, panel.top + TITLE_BASE * S, titlePaint)
        for (r in Symbols.PAGES[focus.page].indices) for (c in Symbols.PAGES[focus.page][r].indices) {
            val l = keyLeft(r, c)
            val rt = keyRight(r, c)
            val kt = keyTop(r)
            canvas.drawRect(l, kt, rt, kt + KEY_H * S, outline)
            val label = if (r == 2 && c == 7) "SYM" else Symbols.ROWS[r][c].toString()
            canvas.drawText(label, (l + rt) / 2, kt + KEY_H * S * 0.72f, keyPaint)
            val lit = Symbols.Focus(focus.page, r, c) == focus
            val gt = glyphTop(r)
            if (lit) {
                canvas.save()
                canvas.translate(l, gt)
                canvas.drawRect(0f, 0f, rt - l, GLYPH_H.toFloat(), focusFill)
                canvas.restore()
            }
            canvas.drawText(Symbols.PAGES[focus.page][r][c].toString(), (l + rt) / 2, gt + GLYPH_H * 0.74f, if (lit) glyphFocused else glyphPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // keys and the trackpad are the way; a touch is a shortcut
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val page = focus.page
        for (r in Symbols.PAGES[page].indices) {
            if (event.y < glyphTop(r) || event.y >= keyTop(r) + KEY_H * S) continue
            for (c in Symbols.PAGES[page][r].indices) if (event.x >= keyLeft(r, c) && event.x < keyRight(r, c)) {
                onChoose(Symbols.Focus(page, r, c))
                return true
            }
        }
        return true
    }

    private companion object {
        const val S = 1.125f // 720 / 640
        const val KEY_GRAY = 0xFFCCCCCC.toInt()
        const val PANEL_W = 452 // 402 x S
        const val PANEL_H = 395 // 351 x S
        val KEY_X0 = floatArrayOf(23f, 39f, 57f)
        val KEY_Y0 = floatArrayOf(95f, 179f, 262f)
        val GLYPH_Y0 = floatArrayOf(52f, 136f, 219f)
        const val KEY_W = 35.6f
        const val SYM_W = 57f
        const val KEY_H = 36f
        const val GLYPH_H = 44 // 39 x S
        const val TITLE_RIGHT = 375f
        const val TITLE_BASE = 42f
    }
}
