package dev.bold7.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.Focus
import dev.bold7.design.MenuColors
import dev.bold7.design.MenuDimens
import dev.bold7.design.Typography

/**
 * The menus of BlackBerry OS 7.1 over a page, the whole page, drawn and touched only while a menu
 * is bound (GONE otherwise), docs/BBOS7-VISUEL.md §4:
 *
 * - the full menu ([Menu.expanded]): border_menu.png (A) against the left edge, its left border and
 *   its bottom off the screen, as wide as its longest label, on the page's bottom and no higher
 *   than [minTop]; rows of 57 px in BBAlpha Sans 27 #E1E3E5, groups apart by a #43454A line, the
 *   focus a blue band with white text; blue arrows when it scrolls (sim-04, sim-44, sim-45, C).
 *   No icon: OS 7's full menu has none (sim-04, sim-44, sim-51, gsm 032, C);
 * - the short menu ([Menu.short]): a box of three columns, centred, each action an sm_*.png image
 *   ([glyph], 82x55, A) over its label in BBAlpha Sans Condensed 17, Full Menu last, the focus a
 *   blue rounded square (sim-17, sim-52, C).
 *
 * The page is not darkened (C). Labels are resolved and wrapped in [bind], shaders made when the
 * layout changes: nothing is allocated in onDraw.
 */
class MenuView<A>(
    context: Context,
    private val glyph: (A) -> Bitmap?,
    private val label: (A) -> String,
    private val onEntry: (Int) -> Unit,
    private val onExpand: () -> Unit,
    private val onClose: () -> Unit,
) : View(context) {
    /** The highest the full menu's top border goes: 3 px under the app's bar on sim-44 (C); the page starts under the bar on the Q25 (D). */
    var minTop: Int = MenuDimens.VIEWPORT_TOP
        set(value) {
            field = value
            relayout()
        }

    private var menu: Menu<A>? = null
    private var labels = emptyArray<String>()
    private var shownLabels = emptyArray<String>()
    private var shortLabels = emptyArray<List<String>>()
    private var full: FullMenuLayout? = null
    private var shortLayout: ShortMenuLayout? = null
    private var scroll = 0
    private var scrollBase = 0
    private var pressed: Int? = null
    private var closing = false // the short menu is fading out: still drawn, [menu] kept until it ends

    // A submenu (sim-223, sim-251): the menu it opened from stays drawn behind it, its focus on the row that opened it.
    private var base: Menu<A>? = null
    private var baseLabels = emptyArray<String>()
    private var baseShown = emptyArray<String>()
    private var baseFull: FullMenuLayout? = null
    private var sub: SubmenuLayout? = null
    private var subFirst = 0
    private val subPanel = themeImage(context, "ui_choice_list")

    private val panel = themeImage(context, "ui_menu")
    private val fullMenuIcon = themeBitmap(context, "ui_sm_full_menu")
    private val fullMenuLabel = listOf(context.getString(R.string.ui_full_menu))

    private val rowFocus = focusPaint(MenuDimens.ROW)
    private val divider = fillPaint(MenuColors.Divider)
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, MenuColors.Text)
    private val textFocused = textPaint(Typography.TEXT, Typography.REGULAR, MenuColors.TextFocused)
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, MenuDimens.ARROW_HEIGHT.toFloat(), MenuColors.ArrowTop, MenuColors.ArrowBottom, Shader.TileMode.CLAMP)
    }
    private val up = triangle(pointingUp = true)
    private val down = triangle(pointingUp = false)
    private val submenuArrow = themeBitmap(context, "ui_menu_arrow")

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MenuColors.ShortFrame }
    private val frameInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MenuColors.ShortFrameInner }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineDark = Paint()
    private val lineLight = Paint()
    private val cellFocus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, MenuDimens.SHORT_FOCUS_HEIGHT.toFloat(), Focus.ShortMenu, Focus.ShortMenuBottom, Shader.TileMode.CLAMP)
    }
    private val shortText = textPaint(MenuDimens.SHORT_LABEL_SIZE, Typography.REGULAR, MenuColors.ShortText, align = Paint.Align.CENTER, family = Typography.FAMILY_CONDENSED)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    init {
        visibility = GONE
        // Drawn on its canvas, without nodes: out of the accessibility tree.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Shows [menu] (null: none). [title] and [subtitle] are not drawn: OS 7's menus have no header. */
    @Suppress("UNUSED_PARAMETER")
    fun bind(menu: Menu<A>?, title: String = "", subtitle: String = "") {
        val old = this.menu
        // The short menu fades out, the page below coming back; the full menu is a cut (docs/BBOS7-VISUEL.md §36).
        if (menu == null && old != null && old.short) {
            if (!closing) {
                closing = true
                pressed = null
                play(MenuFades.CLOSE) {
                    if (closing) {
                        closing = false
                        this.menu = null
                        visibility = GONE
                        alpha = 1f
                        invalidate()
                    }
                }
            }
            return
        }
        closing = false
        animate().cancel()
        alpha = 1f
        if (menu?.entries !== old?.entries) {
            base = if (isSubmenu(old, menu)) old else null
            baseLabels = if (base != null) labels else emptyArray()
            subFirst = 0
            labels = menu?.entries?.map { label(it.action) }?.toTypedArray() ?: emptyArray()
            shortLabels = Array(labels.size) { shortLines(labels[it]) }
            scroll = 0
        }
        this.menu = menu
        if (menu == null) pressed = null
        if (menu?.expanded != old?.expanded || menu?.entries !== old?.entries) relayout() else follow()
        visibility = if (menu == null) GONE else VISIBLE
        invalidate()
        if (old == null && menu?.short == true) play(MenuFades.OPEN)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    /** A short menu label on two lines at most, each no wider than its cell: what the second line cannot hold ends in an ellipsis. */
    private fun shortLines(label: String): List<String> {
        val max = MenuDimens.SHORT_LABEL_MAX.toFloat()
        val lines = wrap(label, max, shortText::measureText)
        val kept = if (lines.size <= 2) lines else listOf(lines[0], lines.drop(1).joinToString(" "))
        return kept.map { shortText.fit(it, max) }
    }

    /** The layouts and shaders of the bound menu at this size: when it, its kind or the size changes, never in onDraw. */
    private fun relayout() {
        val m = menu
        full = null
        shortLayout = null
        sub = null
        baseFull = null
        if (m == null || width == 0) return
        if (m.short) {
            val s = ShortMenuLayout(m.shortCells, width, height)
            shortLayout = s
            val top = s.top.toFloat()
            val bottom = (s.top + s.boxHeight).toFloat()
            boxFill.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(MenuColors.ShortTop, MenuColors.ShortBackground, MenuColors.ShortBottom), null, Shader.TileMode.CLAMP)
            val edge = (s.left + MenuDimens.SHORT_FRAME).toFloat()
            val middle = s.left + s.boxWidth / 2f
            lineDark.shader = LinearGradient(edge, 0f, middle, 0f, MenuColors.ShortLineDarkEdge, MenuColors.ShortLineDark, Shader.TileMode.MIRROR)
            lineLight.shader = LinearGradient(edge, 0f, middle, 0f, MenuColors.ShortLineLightEdge, MenuColors.ShortLineLight, Shader.TileMode.MIRROR)
        } else if (base != null) {
            val b = base!!
            val bl = fullLayout(b, baseLabels)
            baseFull = bl
            baseShown = Array(baseLabels.size) { text.fit(baseLabels[it], TextRoom.menuLabel(bl.right, b.entries[it].submenu)) }
            val widths = FloatArray(labels.size) { text.measureText(labels[it]) }
            val layout = SubmenuLayout(widths, bl.right, width, height, minTop)
            sub = layout
            shownLabels = Array(labels.size) { text.fit(labels[it], TextRoom.submenuLabel(layout.width)) }
        } else {
            val layout = fullLayout(m, labels)
            full = layout
            shownLabels = Array(labels.size) { text.fit(labels[it], TextRoom.menuLabel(layout.right, m.entries[it].submenu)) }
        }
        follow()
    }

    private fun fullLayout(m: Menu<A>, texts: Array<String>): FullMenuLayout {
        val separators = BooleanArray(m.entries.size) { m.entries[it].separatorAbove }
        // A row with a submenu's arrow needs the menu's gap, then the arrow, after its label
        // (the layout adds the last PAD_RIGHT; the arrow sits against the border, sim-292).
        val widths = FloatArray(texts.size) { text.measureText(texts[it]) + if (m.entries[it].submenu) MenuDimens.SUBMENU_ARROW_WIDTH.toFloat() else 0f }
        return FullMenuLayout(widths, separators, height, minTop, TextRoom.menuRight(width))
    }

    /** Keeps the full menu's focused row in sight. */
    private fun follow() {
        sub?.let { subFirst = it.scrollFor(menu?.highlighted ?: 0, subFirst.coerceIn(0, it.maxFirst)) }
        baseFull?.let { scrollBase = it.scrollFor(base?.highlighted ?: 0, scrollBase.coerceIn(0, it.maxScroll)) }
        val f = full ?: return
        scroll = f.scrollFor(menu?.highlighted ?: 0, scroll.coerceIn(0, f.maxScroll))
    }

    override fun onDraw(canvas: Canvas) {
        val m = menu ?: return
        full?.let { drawFull(canvas, m, it, shownLabels, scroll, pressed) }
        baseFull?.let { drawFull(canvas, base!!, it, baseShown, scrollBase, null) }
        sub?.let { drawSub(canvas, m, it) }
        shortLayout?.let { drawShort(canvas, m, it) }
    }

    private fun drawFull(canvas: Canvas, m: Menu<A>, f: FullMenuLayout, shownLabels: Array<String>, scroll: Int, touched: Int?) {
        val right = f.right.toFloat()
        // border_menu.png: its right border pixel is its column 36 of 40, its top one its row 2.
        panel.setBounds(-PANEL_HIDDEN, f.top - 2, f.right + 4, height + PANEL_HIDDEN)
        panel.draw(canvas)
        canvas.save()
        canvas.clipRect(0f, f.viewportTop.toFloat(), right, height.toFloat())
        for (i in 0 until f.count) {
            val y = f.rowY(i, scroll)
            if (y + MenuDimens.ROW < f.viewportTop || y > height) continue
            val lit = i == m.highlighted || i == touched
            if (lit) {
                canvas.save()
                canvas.translate(0f, y.toFloat())
                canvas.drawRect(0f, 0f, right, MenuDimens.ROW.toFloat(), rowFocus)
                canvas.restore()
            }
            f.lineY(i, scroll)?.let { canvas.drawRect(0f, it.toFloat(), right, it + 1f, divider) }
            canvas.drawText(shownLabels[i], MenuDimens.TEXT_X.toFloat(), (y + MenuDimens.TEXT_BASELINE).toFloat(), if (lit) textFocused else text)
            // The same image on the focus band (D: sim-292 shows it unfocused only).
            if (m.entries[i].submenu) canvas.drawBitmap(submenuArrow, right - MenuDimens.SUBMENU_ARROW_WIDTH, (y + MenuDimens.SUBMENU_ARROW_TOP).toFloat(), bitmapPaint)
        }
        canvas.restore()
        val ax = (f.right - MenuDimens.ARROW_RIGHT - MenuDimens.ARROW_WIDTH).toFloat()
        if (scroll > 0) drawArrow(canvas, up, ax, (f.viewportTop + MenuDimens.ARROW_TOP).toFloat())
        // Shown while it scrolls, even at its end (sim-45, C).
        if (f.maxScroll > 0) drawArrow(canvas, down, ax, (height - MenuDimens.ARROW_BOTTOM - MenuDimens.ARROW_HEIGHT).toFloat())
    }

    // border_inplace.png round the rows, the focus the menu's blue band inside it (sim-223, sim-251).
    private fun drawSub(canvas: Canvas, m: Menu<A>, s: SubmenuLayout) {
        subPanel.setBounds(s.left, s.top, s.right, s.bottom)
        subPanel.draw(canvas)
        canvas.save()
        canvas.clipRect(s.left.toFloat(), s.top + MenuDimens.SUB_PAD.toFloat(), s.right.toFloat(), s.bottom - MenuDimens.SUB_PAD.toFloat())
        for (i in subFirst until minOf(s.count, subFirst + s.visible)) {
            val y = s.rowY(i, subFirst)
            val lit = i == m.highlighted || i == pressed
            if (lit) {
                canvas.save()
                canvas.translate(0f, y.toFloat())
                canvas.drawRect((s.left + SUB_BORDER).toFloat(), 0f, (s.right - SUB_BORDER).toFloat(), MenuDimens.ROW.toFloat(), rowFocus)
                canvas.restore()
            }
            canvas.drawText(shownLabels[i], (s.left + MenuDimens.SUB_TEXT_X).toFloat(), (y + MenuDimens.TEXT_BASELINE).toFloat(), if (lit) textFocused else text)
        }
        canvas.restore()
        val ax = (s.right - MenuDimens.ARROW_RIGHT - MenuDimens.ARROW_WIDTH).toFloat()
        if (subFirst > 0) drawArrow(canvas, up, ax, (s.top + (MenuDimens.SUB_PAD - MenuDimens.ARROW_HEIGHT) / 2).toFloat())
        if (subFirst < s.maxFirst) drawArrow(canvas, down, ax, (s.bottom - MenuDimens.ARROW_BOTTOM - MenuDimens.ARROW_HEIGHT).toFloat())
    }

    private fun drawArrow(canvas: Canvas, path: Path, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.drawPath(path, arrow)
        canvas.restore()
    }

    private fun drawShort(canvas: Canvas, m: Menu<A>, s: ShortMenuLayout) {
        val r = MenuDimens.SHORT_RADIUS
        val l = s.left.toFloat()
        val t = s.top.toFloat()
        rect.set(l, t, l + s.boxWidth, t + s.boxHeight)
        canvas.drawRoundRect(rect, r, r, frame)
        rect.inset(1f, 1f)
        canvas.drawRoundRect(rect, r - 1, r - 1, frameInner)
        rect.inset(1f, 1f)
        canvas.drawRoundRect(rect, r - 2, r - 2, boxFill)
        for (row in 0 until s.rows - 1) {
            val y = (s.top + MenuDimens.SHORT_FRAME + (row + 1) * MenuDimens.SHORT_ROW - 1).toFloat()
            canvas.drawRect(rect.left, y, rect.right, y + 1, lineDark)
            canvas.drawRect(rect.left, y + 1, rect.right, y + 2, lineLight)
        }
        val fullCell = s.cells - 1
        for (i in 0 until s.cells) {
            val x = s.cellLeft(i).toFloat()
            val y = s.cellTop(i).toFloat()
            if (i == m.highlighted || i == pressed) {
                canvas.save()
                canvas.translate(x + MenuDimens.SHORT_FOCUS_X, y + MenuDimens.SHORT_FOCUS_Y)
                rect.set(0f, 0f, MenuDimens.SHORT_FOCUS_WIDTH.toFloat(), MenuDimens.SHORT_FOCUS_HEIGHT.toFloat())
                canvas.drawRoundRect(rect, MenuDimens.SHORT_FOCUS_RADIUS, MenuDimens.SHORT_FOCUS_RADIUS, cellFocus)
                canvas.restore()
            }
            val icon = if (i == fullCell) fullMenuIcon else glyph(m.entries[i].action)
            if (icon != null) canvas.drawBitmap(icon, x + (MenuDimens.SHORT_COLUMN - icon.width + 1) / 2, y + MenuDimens.SHORT_ICON_Y, bitmapPaint)
            val lines = if (i == fullCell) fullMenuLabel else shortLabels[i]
            val first = y + MenuDimens.SHORT_LABEL_BASELINE - (lines.size - 1) * MenuDimens.SHORT_LABEL_LINE / 2f
            for (k in lines.indices) canvas.drawText(lines[k], x + MenuDimens.SHORT_COLUMN / 2f, first + k * MenuDimens.SHORT_LABEL_LINE, shortText)
        }
    }

    /** A menu shows and takes the keys, the short one's fade out aside. */
    val open: Boolean get() = menu != null && !closing

    // The keys a menu took do not reach the page when it closes (MenuKeyGate).
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MenuWindowGate.attach(this)
    }

    override fun onDetachedFromWindow() {
        MenuWindowGate.detach(this)
        super.onDetachedFromWindow()
    }

    private var downOnMenu = false

    // Every touch is the menu's while it shows: on it an entry, elsewhere it closes (D).
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = menu ?: return false
        val hit = hit(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOnMenu = onMenu(e.x, e.y)
                setPressed(hit)
            }
            MotionEvent.ACTION_MOVE -> if (pressed != null && pressed != hit) setPressed(null)
            MotionEvent.ACTION_UP -> {
                val p = pressed
                setPressed(null)
                when {
                    p != null && m.short && p == m.shortCells - 1 -> onExpand()
                    p != null -> onEntry(p)
                    !downOnMenu && !onMenu(e.x, e.y) -> onClose()
                }
            }
            MotionEvent.ACTION_CANCEL -> setPressed(null)
        }
        return true
    }

    private fun hit(x: Float, y: Float): Int? {
        sub?.let { return if (x >= it.left && x < it.right) it.rowAt(y, subFirst) else null }
        full?.let { return if (x < it.right) it.rowAt(y, scroll) else null }
        return shortLayout?.cellAt(x, y)
    }

    private fun onMenu(x: Float, y: Float): Boolean {
        sub?.let { return (x >= it.left && x < it.right && y >= it.top && y < it.bottom) || baseFull?.let { b -> x <= b.right && y >= b.top } == true }
        full?.let { return x <= it.right && y >= it.top }
        val s = shortLayout ?: return false
        return x >= s.left && x < s.left + s.boxWidth && y >= s.top && y < s.top + s.boxHeight
    }

    private fun setPressed(p: Int?) {
        if (p == pressed) return
        pressed = p
        invalidate()
    }

    private companion object {
        /** How far border_menu.png's left side and bottom go off the screen: beyond its 18 px corners. */
        const val PANEL_HIDDEN = 24

        /** The submenu focus band stops this far inside its panel's border (D). */
        const val SUB_BORDER = 2

        /** The scroll arrows, 10x5 with a flat tip of 2 px (sim-45, C). */
        fun triangle(pointingUp: Boolean) = Path().apply {
            val w = MenuDimens.ARROW_WIDTH.toFloat()
            val h = MenuDimens.ARROW_HEIGHT.toFloat()
            val tip = if (pointingUp) 0f else h
            val base = h - tip
            moveTo(w / 2 - 1, tip)
            lineTo(w / 2 + 1, tip)
            lineTo(w, base)
            lineTo(0f, base)
            close()
        }
    }
}
