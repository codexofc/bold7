package dev.bold7.calendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.fitSize
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** What the main screen shows: its state, the occurrences read for it, the Agenda's rows, the moment. */
data class Shown(
    val screen: CalendarScreen,
    val occurrences: List<Occurrence>,
    val agenda: List<AgendaRow>,
    val now: LocalDateTime,
    val zone: ZoneId,
    val options: DisplayOptions = DisplayOptions(),
    /** Quick Entry's subject while its box is open on the focused hour of the Day view. */
    val quick: String? = null,
)

/**
 * The Calendar's four views of OS 7, drawn on one canvas (docs/BBOS7-VISUEL.md §29): Day (sim-220,
 * sim-237 to sim-239), Week (sim-224, sim-242), Month (sim-225, sim-243), Agenda (sim-226,
 * sim-244), with [Look]'s measures. It renders [Shown] and decides nothing: the focus and the
 * click are [CalendarScreen]'s; a tap reports the cell or row under the finger ([onTap]).
 *
 * On the square (D): the band and the rows keep the 9900's pixels; the Day and Week views show
 * more hours, the Week view's days share the 720 px, the Month view's cells grow to the height,
 * the Agenda more rows.
 */
class CalendarView(context: Context, private val onTap: (CalendarScreen) -> Unit) : View(context) {
    var shown: Shown? = null
        private set
    var images: Images? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The appointment's bell and the band's arrows (A), decoded off the main thread by the activity. */
    class Images(val bell: Bitmap?, val left: Bitmap?, val right: Bitmap?, val allDay: Bitmap? = null)

    private var dayScroll = -1
    private var agendaScroll = 0

    private val white = fillPaint(ListColors.Background)
    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
    private val textWhite = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
    // The band's title: left aligned at a whole pixel, which keeps its strokes as thin as the 9900's (a centred text starts on a half pixel).
    private val bandTitle = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText).apply { hinting = Paint.HINTING_OFF }
    private val centreWhite = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.CENTER)
    private val small = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.CENTER)
    private val panelSmall = textPaint(Look.PANEL_SMALL, Typography.REGULAR, ListColors.TitleBarText)
    // The hours are BBAlpha Sans Condensed: sim-220's "19" is 26x20, the Sans' is 31x20, the Condensed's 27x20 (C).
    private val hourText = textPaint(Typography.TEXT, Typography.REGULAR, Look.HOUR_TEXT, family = Typography.FAMILY_CONDENSED)
    private val hourLit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, family = Typography.FAMILY_CONDENSED)
    private val corner = textPaint(Typography.TEXT, Typography.BOLD, ListColors.TitleBarText, align = Paint.Align.CENTER)
    private val placeGrey = textPaint(Typography.TEXT, Typography.REGULAR, Look.PLACE)
    private val agendaPlace = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.Subtitle)
    private val right = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title, align = Paint.Align.RIGHT)
    private val rightWhite = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.RIGHT)
    private val rightGrey = textPaint(Typography.TEXT, Typography.REGULAR, Look.OTHER_MONTH_TEXT, align = Paint.Align.RIGHT)
    private val fill = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val round = Paint(Paint.ANTI_ALIAS_FLAG)
    private val header = themeImage(context, "ui_list_header")
    private val rect = RectF()

    fun bind(s: Shown) {
        val old = shown
        shown = s
        if (old == null || old.screen.view != s.screen.view || old.screen.day != s.screen.day) dayScroll = -1
        invalidate()
    }

    // Gradients: made for one height, drawn translated to their top.
    private fun gradient(height: Int, top: Int, bottom: Int) = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)

    private fun vertical(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        fill.shader = gradient((b - t).toInt().coerceAtLeast(1), top, bottom)
        canvas.save()
        canvas.translate(0f, t)
        canvas.drawRect(l, 0f, r, b - t, fill)
        canvas.restore()
        fill.shader = null
    }

    private fun solid(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        canvas.drawRect(l, t, r, b, fill)
    }

    override fun onDraw(canvas: Canvas) {
        val s = shown ?: return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), white)
        when (s.screen.view) {
            CalView.DAY -> drawGrid(canvas, s, listOf(s.screen.day))
            CalView.WEEK -> drawGrid(canvas, s, weekOf(s.screen.day, s.options.firstDay))
            CalView.MONTH -> drawMonth(canvas, s)
            CalView.AGENDA -> drawAgenda(canvas, s)
        }
    }

    // The band of the Day and Month views: a title between two arrows (sim-220, sim-225).
    private fun drawBand(canvas: Canvas, title: String) {
        val w = width.toFloat()
        solid(canvas, 0f, 0f, w, 1f, Look.BAND_LINE)
        vertical(canvas, 0f, 1f, w, Look.BAND.toFloat(), Look.BAND_TOP, Look.BAND_BOTTOM)
        val shownTitle = bandTitle.fit(title, w - 2 * (Look.ARROW_X + 20f))
        canvas.drawText(shownTitle, Math.round((w - bandTitle.measureText(shownTitle)) / 2f).toFloat(), Look.BAND_BASELINE.toFloat(), bandTitle)
        val y = Look.BAND / 2f
        images?.left?.let { canvas.drawBitmap(it, Look.ARROW_X.toFloat(), y - it.height / 2, null) }
        images?.right?.let { canvas.drawBitmap(it, w - Look.ARROW_X - it.width, y - it.height / 2, null) }
    }

    // Day and Week ---------------------------------------------------------------------------------

    // The Week view keeps its all-day row under the band while the hours scroll (sim-224: 17 to 21 under it).
    private fun pinned(s: Shown) = s.screen.view == CalView.WEEK
    private fun gridTop(s: Shown) = if (s.screen.view == CalView.DAY) Look.BAND else Look.WEEK_BAND + Look.WEEK_ROW
    private fun rowHeight(s: Shown) = if (s.screen.view == CalView.DAY) Look.DAY_ROW else Look.WEEK_ROW
    private fun hoursWidth(s: Shown) = if (s.screen.view == CalView.DAY) Look.DAY_HOURS_WIDTH else Look.WEEK_HOURS_WIDTH

    /** The rows' y in the scrolled content: the all-day row first (Week; in the Day view D), then 0 to 23. */
    private fun rowTop(s: Shown, hour: Int) = (hour + 1) * rowHeight(s)

    /** The scroll keeping the focused hour on the screen, with the hour before it above (sim-220: 17 over 18). */
    private fun scrollFor(s: Shown, visible: Int): Int {
        val h = rowHeight(s)
        val focusTop = rowTop(s, s.screen.hour)
        val max = (25 * h - visible).coerceAtLeast(0)
        // Pinned, the all-day row is never in the scrolled part: the hours start at its height.
        val min = if (pinned(s)) h else 0
        if (dayScroll < 0) dayScroll = (focusTop - h).coerceIn(min, max.coerceAtLeast(min))
        if (s.screen.hour != ALL_DAY || !pinned(s)) {
            if (focusTop < dayScroll) dayScroll = focusTop
            if (focusTop + h > dayScroll + visible) dayScroll = focusTop + h - visible
        }
        return dayScroll.coerceIn(min, max.coerceAtLeast(min))
    }

    private fun drawGrid(canvas: Canvas, s: Shown, days: List<LocalDate>) {
        val w = width.toFloat()
        val day = s.screen.day
        val top = gridTop(s)
        val rowH = rowHeight(s)
        val hoursW = hoursWidth(s)
        val colW = (w - hoursW) / days.size
        val focusDay = days.indexOf(day).coerceAtLeast(0)
        val inHour = s.occurrences.filter { it.covers(day, s.screen.hour, s.zone) }
        val focused = inHour.firstOrNull()
        // The panel covers 90 px of the grid: at the bottom while the focus is in the grid's upper
        // part (sim-237), under the band below it (sim-238); the rule between them is D.
        var scroll = scrollFor(s, height - top)
        var focusY = top + rowTop(s, s.screen.hour) - scroll
        val onPinned = pinned(s) && s.screen.hour == ALL_DAY
        val panelOnTop = focused != null && !onPinned && focusY + rowH / 2 >= (top + height) / 2 - rowH / 2
        if (focused != null && !onPinned) {
            if (panelOnTop && focusY < top + Look.PANEL) scroll -= top + Look.PANEL - focusY
            if (!panelOnTop && focusY + rowH > height - Look.PANEL) scroll += focusY + rowH - (height - Look.PANEL)
            scroll = scroll.coerceAtLeast(0)
            dayScroll = scroll
            focusY = top + rowTop(s, s.screen.hour) - scroll
        }

        // The hours' size: "88" stays within the column's edges (a Day column of 36 px holds 28).
        hourText.textSize = Typography.TEXT.toFloat()
        val size = fitSize(Typography.TEXT.toFloat(), hourText.measureText("88"), hoursW - Look.HOUR_X - 2f)
        hourText.textSize = size
        hourLit.textSize = size
        // One pass draws the rows, the all-day one included; the Week view draws it again, pinned.
        fun pass(clipTop: Float, clipBottom: Float, dy: Float) {
        canvas.save()
        canvas.clipRect(0f, clipTop, w, clipBottom)
        canvas.translate(0f, dy)
        // Columns: weekends light grey in the Week view (sim-224).
        for ((i, d) in days.withIndex()) {
            val x = hoursW + i * colW
            if (days.size > 1 && d.dayOfWeek in WEEKEND) solid(canvas, x, 0f, x + colW, 25f * rowH, Look.WEEKEND)
        }
        // The all-day row and the hours.
        for (hour in ALL_DAY..23) {
            val y = rowTop(s, hour).toFloat()
            if (hour == ALL_DAY && days.size > 1) solid(canvas, hoursW.toFloat(), y, w, y + rowH, Look.ALL_DAY_ROW)
            val lit = hour == s.screen.hour
            if (hour == ALL_DAY && days.size > 1 && !lit) {
                solid(canvas, 0f, y, hoursW.toFloat(), y + rowH, Look.ALL_DAY_HOURS)
                images?.allDay?.let { canvas.drawBitmap(it, (hoursW - it.width) / 2f, y + (rowH - it.height) / 2f, null) }
            }
            if (lit) {
                val x0 = if (days.size > 1) hoursW + focusDay * colW else hoursW.toFloat()
                vertical(canvas, x0, y, if (days.size > 1) x0 + colW else w, y + rowH, Look.ROW_FOCUS_TOP, Look.ROW_FOCUS_BOTTOM)
                vertical(canvas, 0f, y, hoursW.toFloat(), y + rowH, Look.HOUR_FOCUS_TOP, Look.HOUR_FOCUS_BOTTOM)
            } else if (hour != ALL_DAY || days.size == 1) {
                solid(canvas, 0f, y, hoursW.toFloat(), y + rowH, Look.HOURS)
            }
            solid(canvas, hoursW.toFloat(), y + rowH - 1, w, y + rowH, if (days.size > 1) Look.WEEK_LINE else Look.DAY_LINE)
            val label = if (hour == ALL_DAY) "" else "%02d".format(hour)
            val hp = if (lit) hourLit else hourText
            canvas.drawText(label, Look.HOUR_X.toFloat() + if (days.size > 1) (hoursW - Look.HOUR_X * 2 - hp.measureText(label)) / 2 else 0f, y + Look.HOUR_BASELINE, hp)
            // The hour of the moment, not focused: its blue frame (sim-239).
            if (!lit && hour == s.now.hour && day == s.now.toLocalDate() && days.size == 1) {
                stroke.color = Look.NOW_FRAME
                canvas.drawRect(0.5f, y + 0.5f, w - 0.5f, y + rowH - 0.5f, stroke)
            }
        }
        for (i in 1 until days.size) {
            val x = hoursW + i * colW - 1
            solid(canvas, x, rowTop(s, ALL_DAY).toFloat(), x + 1, 25f * rowH, if (days[i].dayOfWeek in WEEKEND) Look.WEEKEND_LINE else Look.WEEK_LINE)
        }
        // The appointments of each day.
        for ((i, d) in days.withIndex()) {
            val x0 = hoursW + i * colW
            val allDay = s.occurrences.filter { it.allDay && it.on(d, s.zone) }
            for ((j, o) in allDay.withIndex()) {
                val y = rowTop(s, ALL_DAY).toFloat()
                val bw = (colW - 2 * Look.BLOCK_INSET) / allDay.size
                block(canvas, o, x0 + Look.BLOCK_INSET + j * bw, y + 3, x0 + Look.BLOCK_INSET + (j + 1) * bw - 1, y + rowH - 3, o == focused && d == day, days.size == 1)
            }
            for (b in blocks(d, s.occurrences, s.zone)) {
                val bw = (colW - 2 * Look.BLOCK_INSET) / b.columns
                val l = x0 + Look.BLOCK_INSET + b.column * bw
                val t = rowTop(s, 0) + b.from * rowH / 60f
                val bottom = rowTop(s, 0) + b.to * rowH / 60f
                block(canvas, b.occurrence, l, t, l + bw - 1, bottom - 1, b.occurrence == focused && d == day, days.size == 1)
            }
        }
        canvas.restore()
        }
        pass(top.toFloat(), height.toFloat(), (top - scroll).toFloat())
        if (s.quick != null && days.size == 1) drawQuick(canvas, s, (top + rowTop(s, s.screen.hour) - scroll).toFloat(), top.toFloat())
        if (pinned(s)) pass(Look.WEEK_BAND.toFloat(), top.toFloat(), Look.WEEK_BAND.toFloat())

        // The band over the grid.
        if (days.size == 1) drawBand(canvas, Texts.dayTitle(day)) else drawWeekBand(canvas, s, days, colW)
        if (focused != null) drawPanel(canvas, s, focused, if (panelOnTop) top.toFloat() else (height - Look.PANEL).toFloat())
    }

    /**
     * Quick Entry's box on the focused hour (sim-227, C): the row's width and height framed by a 1 px
     * line, the hour and the next in white on the blue of the focused hour (82 px wide), the subject
     * in black on a light gradient, a 9x32 block cursor after it.
     */
    private fun drawQuick(canvas: Canvas, s: Shown, y: Float, clipTop: Float) {
        val w = width.toFloat()
        val h = rowHeight(s).toFloat()
        val subject = s.quick ?: return
        canvas.save()
        canvas.clipRect(0f, clipTop, w, height.toFloat())
        solid(canvas, 0f, y, w, y + h, Look.QUICK_FRAME)
        vertical(canvas, 1f, y + 1, Look.QUICK_TIMES, y + h - 1, Look.HOUR_FOCUS_TOP, Look.HOUR_FOCUS_BOTTOM)
        vertical(canvas, Look.QUICK_TIMES + 1, y + 1, w - 1, y + h - 1, Look.QUICK_FIELD_TOP, Look.QUICK_FIELD_BOTTOM)
        val hour = s.screen.hour
        canvas.drawText(Texts.time(java.time.LocalTime.of(hour, 0)), Look.QUICK_TIME_X.toFloat(), y + Look.QUICK_BASELINE, textWhite)
        canvas.drawText(Texts.time(java.time.LocalTime.of((hour + 1) % 24, 0)), Look.QUICK_TIME_X.toFloat(), y + Look.QUICK_BASELINE_2, textWhite)
        // A subject longer than the field shows its end, where the cursor is.
        val room = w - Look.QUICK_TEXT_X - Look.QUICK_CURSOR_W - 1
        var shownText = subject
        while (shownText.length > 1 && text.measureText(shownText) > room) shownText = shownText.drop(1)
        canvas.drawText(shownText, Look.QUICK_TEXT_X.toFloat(), y + Look.QUICK_BASELINE, text)
        val cursor = Look.QUICK_TEXT_X + text.measureText(shownText)
        vertical(canvas, cursor, y + 1, cursor + Look.QUICK_CURSOR_W, y + 1 + Look.QUICK_CURSOR_H, Look.HOUR_FOCUS_TOP, Look.HOUR_FOCUS_BOTTOM)
        canvas.restore()
    }

    private fun drawWeekBand(canvas: Canvas, s: Shown, days: List<LocalDate>, colW: Float) {
        val w = width.toFloat()
        val h = Look.WEEK_BAND.toFloat()
        solid(canvas, 0f, 0f, w, 1f, Look.BAND_LINE)
        vertical(canvas, 0f, 1f, w, h, Look.BAND_TOP, Look.BAND_BOTTOM)
        val (month, year) = Texts.weekCorner(s.screen.day)
        // Month and year in bold, whole in the 66 px column with 4 px each side (sim-224: x 4 to 61); the
        // Q25's font is wider than the 9900's, so the size follows the wider of the two.
        val hw = Look.WEEK_HOURS_WIDTH / 2f
        val room = Look.WEEK_HOURS_WIDTH - 2f * Look.CORNER_MARGIN
        corner.textSize = Typography.TEXT.toFloat()
        corner.textSize = fitSize(Typography.TEXT.toFloat(), maxOf(corner.measureText(month), corner.measureText(year)), room)
        canvas.drawText(corner.fit(month, room), hw, Look.WEEK_NUMBER_BASELINE.toFloat(), corner)
        canvas.drawText(corner.fit(year, room), hw, Look.WEEK_NAME_BASELINE.toFloat(), corner)
        for ((i, d) in days.withIndex()) {
            val x = Look.WEEK_HOURS_WIDTH + i * colW
            if (d == s.screen.day) vertical(canvas, x, 0f, x + colW, h, Look.HOUR_FOCUS_TOP, Look.HOUR_FOCUS_BOTTOM)
            canvas.drawText("${d.dayOfMonth}", x + colW / 2, Look.WEEK_NUMBER_BASELINE.toFloat(), centreWhite)
            canvas.drawText(Texts.weekday(d), x + colW / 2, Look.WEEK_NAME_BASELINE.toFloat(), small)
        }
    }

    /** An appointment's block (sim-237, sim-239); [wide]: the Day view, where its subject and place are written. */
    private fun block(canvas: Canvas, o: Occurrence, l: Float, t: Float, r: Float, b: Float, lit: Boolean, wide: Boolean) {
        rect.set(l, t, r, b)
        round.shader = gradient((b - t).toInt().coerceAtLeast(1), if (lit) Look.BLOCK_TOP else Look.BLOCK_IDLE_TOP, if (lit) Look.BLOCK_BOTTOM else Look.BLOCK_IDLE_BOTTOM)
        canvas.save()
        canvas.translate(0f, t)
        rect.offset(0f, -t)
        canvas.drawRoundRect(rect, Look.BLOCK_RADIUS, Look.BLOCK_RADIUS, round)
        stroke.color = if (lit) Look.BLOCK_BORDER else Look.BLOCK_IDLE_BORDER
        canvas.drawRoundRect(rect, Look.BLOCK_RADIUS, Look.BLOCK_RADIUS, stroke)
        canvas.restore()
        round.shader = null
        val barTop = t + 4
        val barBottom = minOf(b - 4, t + 28)
        if (barBottom > barTop) {
            solid(canvas, l + Look.BLOCK_BAR_X, barTop, l + Look.BLOCK_BAR_X + Look.BLOCK_BAR_WIDTH, barBottom, Look.BLOCK_BAR_EDGE)
            solid(canvas, l + Look.BLOCK_BAR_X + 1, barTop, l + Look.BLOCK_BAR_X + Look.BLOCK_BAR_WIDTH - 1, barBottom, Look.BLOCK_BAR)
        }
        if (!wide) return
        canvas.save()
        canvas.clipRect(l, t, r - 4, b)
        val x = l + Look.BLOCK_TEXT_X
        val y = t + Look.BLOCK_BASELINE
        val room = r - 4 - x
        val title = o.title.ifEmpty { context.getString(R.string.no_subject) }
        if (lit) {
            canvas.drawText(textWhite.fit(Texts.subject(title, o.location, ""), room), x, y, textWhite)
        } else {
            val shown = text.fit(title, room)
            canvas.drawText(shown, x, y, text)
            val left = room - text.measureText(shown)
            if (o.location.isNotEmpty() && shown == title) canvas.drawText(placeGrey.fit(" (${o.location})", left), x + text.measureText(title), y, placeGrey)
        }
        canvas.restore()
    }

    /** The focused appointment's panel (sim-237: at the bottom; sim-238: under the band). */
    private fun drawPanel(canvas: Canvas, s: Shown, o: Occurrence, top: Float) {
        val w = width.toFloat()
        solid(canvas, 0f, top, w, top + Look.PANEL, Look.PANEL_COLOR)
        solid(canvas, 5f, top + 4, 5f + Look.BLOCK_BAR_WIDTH, top + 34, Look.BLOCK_BAR_EDGE)
        solid(canvas, 6f, top + 4, 4f + Look.BLOCK_BAR_WIDTH, top + 34, Look.BLOCK_BAR)
        val x = Look.PANEL_TEXT_X.toFloat()
        canvas.save()
        canvas.clipRect(0f, top, w - 30, top + Look.PANEL)
        val room = w - 30 - x
        canvas.drawText(textWhite.fit(o.title.ifEmpty { context.getString(R.string.no_subject) }, room), x, top + Look.PANEL_TITLE_BASELINE, textWhite)
        canvas.drawText(panelSmall.fit(Texts.span(o.start(s.zone), o.finish(s.zone), o.allDay), room), x, top + Look.PANEL_TIME_BASELINE, panelSmall)
        canvas.drawText(panelSmall.fit(o.location, room), x, top + Look.PANEL_PLACE_BASELINE, panelSmall)
        canvas.restore()
        if (o.reminder) images?.bell?.let { canvas.drawBitmap(it, w - 20 - it.width / 2f, top + Look.PANEL_TIME_BASELINE - it.height, null) }
    }

    // Month ----------------------------------------------------------------------------------------

    private fun monthCell(s: Shown): Pair<Float, Float> {
        val top = Look.BAND + Look.NAMES
        return width / 7f to (height - top) / 6f
    }

    private fun drawMonth(canvas: Canvas, s: Shown) {
        val w = width.toFloat()
        val grid = monthGrid(s.screen.day, s.options.firstDay)
        drawBand(canvas, Texts.monthTitle(s.screen.day))
        val top = Look.BAND + Look.NAMES
        solid(canvas, 0f, Look.BAND.toFloat(), w, top.toFloat(), Look.NAMES_COLOR)
        val (cw, ch) = monthCell(s)
        for (i in 0 until 7) canvas.drawText(Texts.weekday(grid[i]), cw * i + cw / 2, Look.BAND + Look.NAMES - 8f, small)
        val busy = grid.associateWith { d -> s.occurrences.any { it.on(d, s.zone) } }
        for ((i, d) in grid.withIndex()) {
            val l = cw * (i % 7)
            val t = top + ch * (i / 7)
            val other = d.month != s.screen.day.month
            val lit = d == s.screen.day
            when {
                lit -> vertical(canvas, l, t, l + cw - 1, t + ch - 1, Look.DAY_FOCUS_TOP, Look.DAY_FOCUS_BOTTOM)
                other -> solid(canvas, l, t, l + cw, t + ch, Look.OTHER_MONTH)
                d.dayOfWeek in WEEKEND -> solid(canvas, l, t, l + cw, t + ch, Look.WEEKEND)
            }
            solid(canvas, l + cw - 1, t, l + cw, t + ch, Look.MONTH_LINE)
            solid(canvas, l, t + ch - 1, l + cw, t + ch, Look.MONTH_LINE)
            canvas.drawText("${d.dayOfMonth}", l + cw - Look.NUMBER_RIGHT, t + Look.NUMBER_BASELINE, if (lit) rightWhite else if (other) rightGrey else right)
            if (monthMark(busy[d] == true, lit)) {
                val mx = l + Look.MARK_X
                val my = t + ch - Look.MARK_BOTTOM
                solid(canvas, mx - 1, my - 1, mx + Look.MARK_WIDTH + 1, my + Look.MARK_HEIGHT + 1, Look.MARK_FRAME)
                solid(canvas, mx, my, mx + Look.MARK_WIDTH, my + Look.MARK_HEIGHT, ListColors.Background)
            }
        }
    }

    // Agenda ---------------------------------------------------------------------------------------

    private fun agendaHeight(r: AgendaRow) = when (r) {
        is AgendaRow.Header -> dev.bold7.ui.ListHeaderView.HEIGHT
        is AgendaRow.Free -> Look.FREE_ROW
        is AgendaRow.Event -> Look.EVENT_ROW
    }

    private fun drawAgenda(canvas: Canvas, s: Shown) {
        val w = width.toFloat()
        val rows = s.agenda
        val tops = IntArray(rows.size + 1).also { for (i in rows.indices) it[i + 1] = it[i] + agendaHeight(rows[i]) }
        val f = s.screen.row.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        // Keep the focused row on the screen; the list opens on its top (sim-226).
        if (rows.isNotEmpty()) {
            if (tops[f] < agendaScroll) agendaScroll = tops[f]
            if (tops[f + 1] > agendaScroll + height) agendaScroll = tops[f + 1] - height
        }
        canvas.save()
        canvas.translate(0f, -agendaScroll.toFloat())
        for ((i, r) in rows.withIndex()) {
            val t = tops[i].toFloat()
            if (tops[i + 1] < agendaScroll || t > agendaScroll + height) continue
            val h = agendaHeight(r).toFloat()
            val lit = i == f
            when (r) {
                is AgendaRow.Header -> {
                    header.setBounds(0, t.toInt(), width, t.toInt() + dev.bold7.design.ListDimens.HEADER)
                    if (lit) {
                        canvas.save(); canvas.translate(0f, t); canvas.drawRect(0f, 0f, w, dev.bold7.design.ListDimens.HEADER.toFloat(), focusPaint(dev.bold7.design.ListDimens.HEADER)); canvas.restore()
                    } else header.draw(canvas)
                    solid(canvas, 0f, t + dev.bold7.design.ListDimens.HEADER, w, t + h, ListColors.Divider)
                    canvas.drawText(Texts.shortDay(r.day), w / 2, t + dev.bold7.design.ListDimens.HEADER_BASELINE, if (lit) centreWhite else centreBlack)
                }
                is AgendaRow.Free -> {
                    if (lit) { canvas.save(); canvas.translate(0f, t); canvas.drawRect(0f, 0f, w, h, focusPaint(h.toInt())); canvas.restore() } else solid(canvas, 0f, t, w, t + h, Look.FREE)
                    val freeText = context.getString(R.string.free_time)
                    val span = "${Texts.time(r.from)} - ${Texts.time(r.to)}"
                    canvas.drawText(text.fit(span, w - Look.AGENDA_RIGHT - Look.AGENDA_TEXT_X - right.measureText(freeText) - 16f), Look.AGENDA_TEXT_X.toFloat(), t + Look.FREE_BASELINE, if (lit) textWhite else text)
                    canvas.drawText(freeText, w - Look.AGENDA_RIGHT, t + Look.FREE_BASELINE, if (lit) rightWhite else right)
                }
                is AgendaRow.Event -> {
                    val o = r.occurrence
                    if (lit) { canvas.save(); canvas.translate(0f, t); canvas.drawRect(0f, 0f, w, h, focusPaint(h.toInt())); canvas.restore() }
                    solid(canvas, Look.AGENDA_BAR_X.toFloat(), t + 5, Look.AGENDA_BAR_X + 6f, t + 35, Look.BLOCK_BAR_EDGE)
                    solid(canvas, Look.AGENDA_BAR_X + 1f, t + 5, Look.AGENDA_BAR_X + 5f, t + 35, Look.BLOCK_BAR)
                    canvas.save()
                    canvas.clipRect(0f, t, w - 40, t + h)
                    val x = Look.AGENDA_TEXT_X.toFloat()
                    val room = w - 40 - x
                    canvas.drawText(text.fit(o.title.ifEmpty { context.getString(R.string.no_subject) }, room), x, t + Look.EVENT_TITLE_BASELINE, if (lit) textWhite else text)
                    canvas.drawText(text.fit(Texts.span(o.start(s.zone), o.finish(s.zone), o.allDay), room), x, t + Look.EVENT_TIME_BASELINE, if (lit) textWhite else text)
                    canvas.drawText(agendaPlace.fit(o.location, room), x, t + Look.EVENT_PLACE_BASELINE, if (lit) textWhite else agendaPlace)
                    canvas.restore()
                    if (o.reminder) images?.bell?.let { canvas.drawBitmap(it, w - 20 - it.width / 2f, t + Look.EVENT_TIME_BASELINE - it.height, null) }
                    solid(canvas, 0f, t + h - 1, w, t + h, ListColors.Divider)
                }
            }
        }
        canvas.restore()
    }

    private val centreBlack = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title, align = Paint.Align.CENTER)

    // Touch (D): a tap focuses what is under the finger; a tap on the focus is the click (CalendarActivity).

    fun tap(x: Float, y: Float) {
        val s = shown ?: return
        val sc = s.screen
        when (sc.view) {
            CalView.DAY, CalView.WEEK -> {
                val top = gridTop(s)
                val pinnedTop = Look.WEEK_BAND
                if (y < (if (pinned(s)) pinnedTop else top)) return
                val days = if (sc.view == CalView.DAY) listOf(sc.day) else weekOf(sc.day, s.options.firstDay)
                val colW = (width - hoursWidth(s)) / days.size.toFloat()
                val i = ((x - hoursWidth(s)) / colW).toInt().coerceIn(0, days.size - 1)
                val hour = if (pinned(s) && y < top) ALL_DAY else (((y - top + dayScroll) / rowHeight(s)).toInt() - 1).coerceIn(ALL_DAY, 23)
                onTap(sc.copy(day = days[i], hour = hour))
            }
            CalView.MONTH -> {
                val top = Look.BAND + Look.NAMES
                if (y < top) return
                val (cw, ch) = monthCell(s)
                val i = ((y - top) / ch).toInt().coerceIn(0, 5) * 7 + (x / cw).toInt().coerceIn(0, 6)
                onTap(sc.copy(day = monthGrid(sc.day, s.options.firstDay)[i]))
            }
            CalView.AGENDA -> {
                var t = -agendaScroll
                for ((i, r) in s.agenda.withIndex()) {
                    val h = agendaHeight(r)
                    if (y >= t && y < t + h) return onTap(sc.copy(row = i))
                    t += h
                }
            }
        }
    }

    private companion object {
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
