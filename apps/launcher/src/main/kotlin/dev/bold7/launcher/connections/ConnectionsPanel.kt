package dev.bold7.launcher.connections

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import dev.bold7.design.ConnectionColors
import dev.bold7.design.DialogColors
import dev.bold7.design.DialogDimens
import dev.bold7.design.HomeDimens
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.design.toInterpolator
import dev.bold7.launcher.quickstep.Transitions
import dev.bold7.launcher.R
import dev.bold7.platform.Connection
import dev.bold7.ui.themeBitmap
import dev.bold7.ui.FocusDialog
import dev.bold7.ui.Focused
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage
import kotlin.math.abs
import dev.bold7.launcher.connections.ConnectionsLayout as L

/**
 * Manage Connections (docs/BBOS7-VISUEL.md §6; sim-23, sim-24, sim-112 to sim-120, gsm 006 and 007,
 * C): white group boxes on grey under the banner, which stays visible. Manage Connections, a check
 * box per connection with its glyph and status, All Off (Restore after it); Set Clock Alarm;
 * Options and Status. The trackpad's focus goes from the banner down: on a box only its check
 * box turns blue, on the button the button, on Alarm and the entries a blue band (sim-112 to
 * sim-116). The panel scrolls just enough to show the focus; the finger scrolls it as Android's
 * lists do. It renders [state] and only draws.
 */
internal class ConnectionsView(
    context: Context,
    /** The trackpad's focus reached or left the banner: the home draws its focus there, or not. */
    private val onBannerFocus: (Boolean) -> Unit,
) : ScrollView(context), Focused {
    var state = ConnectionsState(emptySet())
        set(value) {
            if (value == field) return
            val relayout = value.boxes.size != field.boxes.size
            field = value
            if (relayout) {
                layout = null
                content.requestLayout()
            }
            focused = focused // the items may have changed
            content.invalidate()
        }

    override var focused: Int = 0
        set(value) {
            val next = value.coerceIn(0, state.items.size - 1)
            val was = field
            field = next
            if ((was == 0) != (next == 0)) onBannerFocus(next == 0)
            show(next)
            content.invalidate()
        }

    /** A tap: the item under it, or null outside the panel (over the banner). */
    var onTap: (Int?) -> Unit = {}

    private var layout: L? = null
    private val content = Content()
    private var outside = false

    init {
        // The banner shows through the padding, over it nothing is drawn.
        setPadding(0, HomeDimens.BANNER, 0, 0)
        clipToPadding = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun layout(): L = layout ?: L(width, state.boxes.size).also { layout = it }

    private fun show(i: Int) {
        if (width == 0) return
        val y = layout().scrollFor(i, scrollY, height - paddingTop)
        if (y != scrollY) scrollTo(0, y)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layout = null
        show(focused)
    }

    // A touch over the banner closes the panel (D: the 9900 has no touch there); the rest is the list's.
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) outside = e.y < paddingTop
        if (!outside) return super.dispatchTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) onTap(null)
        return true
    }

    /**
     * The panel goes up and out under the banner, at constant speed (§ 36, C),
     * then [onEnd]; the banner stays. The content moves, the padding's clip keeps it under the banner.
     */
    fun slideUp(onEnd: () -> Unit) {
        content.animate().translationY(-content.height.toFloat()).setDuration(Transitions.CONNECTIONS_CLOSE_MS)
            .setInterpolator(Transitions.CONNECTIONS_CLOSE_CURVE.toInterpolator()).withEndAction(onEnd).start()
    }

    /** The panel, drawn on one canvas. */
    private inner class Content : View(context) {
        private val box = themeImage(context, R.drawable.connections_box)
        private val titleBar = themeImage(context, R.drawable.connections_title)
        private val glyphs: Bitmap = (themeImage(context, R.drawable.connections_icons) as BitmapDrawable).bitmap
        private val check: Bitmap = themeBitmap(context, "ui_check")
        private val button = themeImage(context, "ui_button")
        private val buttonFocused = themeImage(context, "ui_button_focus")

        private val grey = fillPaint(ListColors.GroupBackground)
        private val line = fillPaint(ListColors.Divider)
        // Alarm's band and the entries' (sim-114, sim-116, C).
        private val alarmBand = focusPaint(L.ALARM_ROW - 4)
        private val entryBand = focusPaint(L.ENTRY_ROW - 4)
        private val title = textPaint(Typography.SUBTITLE, Typography.BOLD, ListColors.GroupTitle)
        private val label = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title)
        private val labelLit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText)
        private val status = textPaint(Typography.TEXT, Typography.REGULAR, ConnectionColors.Status, align = Paint.Align.RIGHT)
        private val value = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Title, align = Paint.Align.RIGHT)
        private val valueLit = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.TitleBarText, align = Paint.Align.RIGHT)
        private val subtitle = textPaint(Typography.SUBTITLE, Typography.REGULAR, ConnectionColors.Subtitle)
        private val subtitleLit = textPaint(Typography.SUBTITLE, Typography.REGULAR, ListColors.TitleBarText)
        private val buttonText = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
        private val buttonTextLit = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
        private val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = Rect()
        private val dst = Rect()

        private val s = context.resources
        private val titles = listOf(R.string.connections_title, R.string.connections_alarm_title, R.string.connections_options_title).map(s::getString)
        private val names = Connection.entries.associateWith {
            s.getString(
                when (it) {
                    Connection.MOBILE -> R.string.connections_mobile
                    Connection.WIFI -> R.string.connections_wifi
                    Connection.BLUETOOTH -> R.string.connections_bluetooth
                    Connection.NFC -> R.string.connections_nfc
                },
            )
        }
        private val on = s.getString(R.string.connections_on)
        private val allOff = s.getString(R.string.connections_all_off)
        private val restore = s.getString(R.string.connections_restore)
        private val alarm = s.getString(R.string.connections_alarm)
        private val alarmOn = s.getString(R.string.connections_alarm_on)
        private val alarmOff = s.getString(R.string.connections_alarm_off)
        private val entries = listOf(
            R.string.connections_networks to R.string.connections_networks_sub,
            R.string.connections_wifi_network to R.string.connections_wifi_network_sub,
            R.string.connections_service to R.string.connections_service_sub,
            R.string.connections_options to R.string.connections_options_sub,
        ).map { (t, st) -> s.getString(t) to s.getString(st) }

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            setMeasuredDimension(w, L(w, state.boxes.size).height)
        }

        private fun buttonLeft(l: L) = l.buttonRight - buttonText.measureText(if (state.restoring) restore else allOff).toInt() - 2 * L.BUTTON_PAD

        override fun onDraw(canvas: Canvas) {
            val l = layout()
            val w = width
            canvas.drawRect(0f, 0f, w.toFloat(), height.toFloat(), grey)
            for (b in 0..2) {
                box.setBounds(0, l.boxTop(b), w, l.boxEdge(b) + 3)
                box.draw(canvas)
                titleBar.setBounds(0, l.boxTop(b), w, l.boxTop(b) + L.TITLE)
                titleBar.draw(canvas)
                canvas.drawText(title.fit(titles[b], l.titleRoom()), L.TITLE_X.toFloat(), l.titleBaseline(b).toFloat(), title)
            }
            // Manage Connections: the boxes, their lines, the button.
            for ((k, c) in state.boxes.withIndex()) {
                val top = l.rowTop(k)
                glyph(canvas, glyphColumn(c), false, L.ICON_X, top + (L.CHECK_ROW - L.ICON) / 2)
                val name = label.fit(names.getValue(c), l.labelRoom())
                canvas.drawText(name, L.LABEL_X.toFloat(), (top + L.CHECK_BASELINE).toFloat(), label)
                state.status(c, on)?.let { canvas.drawText(status.fit(it, l.statusRoom(label.measureText(name))), l.statusRight.toFloat(), (top + L.CHECK_BASELINE).toFloat(), status) }
                val box = 27
                val column = if (focused == k + 1) 1 else 0
                val row = if (state.isOn(c)) 1 else 0
                src.set(column * box, row * box, (column + 1) * box, (row + 1) * box)
                val y = top + (L.CHECK_ROW - box) / 2
                dst.set(l.checkX, y, l.checkX + box, y + box)
                canvas.drawBitmap(check, src, dst, bitmap)
            }
            for (y in l.boxLines) line(canvas, l, y)
            val lit = focused == state.boxes.size + 1
            val left = buttonLeft(l)
            (if (lit) buttonFocused else button).apply {
                setBounds(left - 3, l.buttonTop - 3, l.buttonRight + 3, l.buttonTop + L.BUTTON_HEIGHT + 3)
                draw(canvas)
            }
            canvas.drawText(if (state.restoring) restore else allOff, (left + l.buttonRight) / 2f, (l.buttonTop + DialogDimens.BUTTON_BASELINE).toFloat(), if (lit) buttonTextLit else buttonText)
            // Set Clock Alarm.
            val alarmLit = bandIf(canvas, l, state.boxes.size + 2)
            glyph(canvas, 3, alarmLit, L.ICON_X, l.alarmTop + (L.ALARM_ROW - L.ICON) / 2)
            val alarmY = (l.alarmTop + L.ALARM_BASELINE).toFloat()
            val alarmValue = if (state.readings.alarm) alarmOn else alarmOff
            canvas.drawText(label.fit(alarm, l.alarmRoom(value.measureText(alarmValue))), L.LABEL_X.toFloat(), alarmY, if (alarmLit) labelLit else label)
            canvas.drawText(alarmValue, (w - 12).toFloat(), alarmY, if (alarmLit) valueLit else value)
            // Options and Status.
            for (y in l.entryLines) line(canvas, l, y)
            for ((k, e) in entries.withIndex()) {
                val lit = bandIf(canvas, l, state.boxes.size + 3 + k)
                val top = l.entryTop(k)
                canvas.drawText(label.fit(e.first, l.entryRoom()), L.TEXT_X.toFloat(), (top + L.ENTRY_TITLE_BASELINE).toFloat(), if (lit) labelLit else label)
                canvas.drawText(subtitle.fit(e.second, l.entryRoom()), L.TEXT_X.toFloat(), (top + L.ENTRY_SUBTITLE_BASELINE).toFloat(), if (lit) subtitleLit else subtitle)
            }
        }

        private fun line(canvas: Canvas, l: L, y: Int) =
            canvas.drawRect(l.lineLeft.toFloat(), y.toFloat(), (l.lineRight + 1).toFloat(), (y + 1).toFloat(), line)

        /** The blue band of item [i] if it has the focus; whether it has. */
        private fun bandIf(canvas: Canvas, l: L, i: Int): Boolean {
            if (focused != i) return false
            val (top, bottom) = l.band(l.slot(i) ?: return false)
            val band = if (i == state.boxes.size + 2) alarmBand else entryBand
            canvas.save()
            canvas.translate(l.lineLeft.toFloat(), top.toFloat())
            canvas.drawRect(0f, 0f, (l.lineRight + 1 - l.lineLeft).toFloat(), (bottom - top).toFloat(), band)
            canvas.restore()
            return true
        }

        /** A 40 px glyph of net_rim_Manage_Connections-icons40x40 (A): its column, its white row on the focus. */
        private fun glyph(canvas: Canvas, column: Int, lit: Boolean, x: Int, y: Int) {
            val row = if (lit) 1 else 0
            src.set(column * L.ICON, row * L.ICON, (column + 1) * L.ICON, (row + 1) * L.ICON)
            dst.set(x, y, x + L.ICON, y + L.ICON)
            canvas.drawBitmap(glyphs, src, dst, bitmap)
        }

        private fun glyphColumn(c: Connection) = when (c) {
            Connection.MOBILE -> 0
            Connection.WIFI -> 1
            Connection.BLUETOOTH -> 2
            Connection.NFC -> 4
        }

        // A tap: its item. A drag is the scroll's.
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                }
                MotionEvent.ACTION_UP -> if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                    val l = layout()
                    l.itemAt(e.x, e.y, buttonLeft(l))?.let(onTap)
                }
            }
            return true
        }
    }
}

/**
 * The panel as a window over the home (as "Select a Profile"), its focus and keys those of
 * [FocusDialog]; the banner stays visible above it. A box or the button acts and the panel stays
 * (sim-117, sim-119, C); Alarm and the entries open their page and close it; a click with the
 * focus back on the banner, Escape, or a touch over the banner close it, going up in 190 ms (opening: no animation).
 */
internal class ConnectionsPanel(
    context: Context,
    private val controller: ConnectionsController,
    private val openPage: (Page) -> Unit,
    onBannerFocus: (Boolean) -> Unit,
) : FocusDialog<ConnectionsView>(
    context,
    ConnectionsView(context, onBannerFocus),
    // The most items there can be; the view keeps the focus within those there are.
    1 + Connection.entries.size + 2 + ENTRIES.size,
    hideStatusBar = true,
) {
    init {
        window?.setTitle(context.getString(R.string.connections_title))
        // The list scrolls under the finger: the taps come from the view, not from FocusDialog's listener.
        view.setOnTouchListener(null)
        view.onTap = { if (it == null) cancel() else choose(it) }
        controller.open { view.state = it }
        setOnDismissListener {
            controller.close()
            onBannerFocus(true) // the home draws its focus again, where it was
        }
    }

    override fun itemAt(x: Float, y: Float): Int? = null

    private var leaving = false

    // Escape, a touch over the banner or the banner's click: the panel goes up (190 ms, constant
    // speed, § 36); dismiss() stays at once for the activity's own closings.
    override fun cancel() {
        if (leaving) return
        if (!isShowing) return super.cancel()
        leaving = true
        view.slideUp { if (isShowing) super.cancel() }
    }

    override fun closes(item: Int) = view.state.items.getOrNull(item)?.closes ?: true

    override fun onChosen(item: Int) {
        view.state.items.getOrNull(item)?.let { controller.click(it, openPage) }
    }
}
