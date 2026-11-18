package dev.bold7.launcher.notices

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import dev.bold7.design.NoticeColors
import dev.bold7.design.NoticeDimens as D
import dev.bold7.design.Typography
import dev.bold7.hub.data.HubItem
import dev.bold7.launcher.home.HomeView
import dev.bold7.launcher.home.UNREAD_OTHER
import dev.bold7.launcher.hub.ItemOpener
import dev.bold7.launcher.hub.Opened
import dev.bold7.launcher.keys.HomeList
import dev.bold7.ui.Chunk
import dev.bold7.ui.Dir
import dev.bold7.ui.EmoticonPen
import dev.bold7.ui.EmoticonStrip
import dev.bold7.ui.emoticonChunks
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.fitEmoticons
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeBitmap
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The list of notifications that drops under the home's indicator row (docs/BBOS7-VISUEL.md §5,
 * sim-26, sim-27, C): over the darkened wallpaper, the tray gone ([HomeView.trayShown]), a header
 * per app and a row per unopened item, the trackpad's focus a blue band across. It renders
 * [lines] (Notices.kt, worked out with the Hub's read) and only draws: nothing is allocated in
 * onDraw. A tap or a click on a row opens its item in its app and marks it opened (the row goes,
 * sim-104, C); on a header, its app. Opened by a tap or a click on the row's zone (which closes it
 * again, sim-107, C) or a pull down on the banner (D); closed by Escape (sim-106, C), a tap under
 * its lines (D), or the home leaving the screen. Nothing opens while the phone is locked.
 */
internal class NoticesView(
    context: Context,
    private val home: HomeView,
    private val opener: ItemOpener,
    private val markRead: (HubItem) -> Unit,
    /** An app's home icon by package, already drawn by the home's source (main thread, no work); null if none. */
    private val appIcon: (String) -> Bitmap?,
    io: CoroutineDispatcher,
    scope: CoroutineScope,
    /** Closed with a line focused: the home's focus is drawn again (HomeKeys.refocus). */
    private val onClosed: () -> Unit,
) : ScrollView(context), HomeList {
    private var lines: List<NoticeLine> = emptyList()
    private var focus: Int? = null
    private val content = Lines()

    override val isOpen get() = visibility == VISIBLE

    init {
        visibility = GONE
        setBackgroundColor(NoticeColors.Veil)
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true // a tap under the lines lands on them, and closes
        isFocusable = false // the trackpad's focus stays the home's (HomeKeys)
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scope.launch {
            content.strip = withContext(io) { themeBitmap(context, "home_net_rim_ribbon_unreadcount_icons33x29") }
            content.invalidate()
        }
    }

    /** The list, nothing if there is nothing to show (D) or the phone is locked. */
    fun open() {
        if (isOpen || lines.isEmpty() || opener.locked()) return
        focus = null
        scrollTo(0, 0)
        visibility = VISIBLE
        home.trayShown(false)
    }

    fun close() {
        if (!isOpen) return
        val focused = focus != null
        focus = null
        visibility = GONE
        home.trayShown(true)
        if (focused) onClosed()
    }

    fun toggle() = if (isOpen) close() else open()

    /** A new read of the Hub: the list again, the focus kept in it; emptied, it closes. */
    fun render(next: List<NoticeLine>) {
        if (next == lines) return
        lines = next
        focus = noticeFocus(focus, next.size)
        content.build()
        if (next.isEmpty()) close()
    }

    override val hasLines get() = lines.isNotEmpty()

    override val lineFocused get() = isOpen && focus != null

    override fun step(dir: Dir): Boolean {
        if (!isOpen) return false
        if (dir == Dir.LEFT || dir == Dir.RIGHT) return focus != null
        if (dir == Dir.UP && focus == null) return false
        focus = noticeStep(focus, lines.size, down = dir == Dir.DOWN)
        focus?.let { show(it) }
        content.invalidate()
        return true
    }

    override fun click(): Boolean {
        val at = focus.takeIf { isOpen } ?: return false
        activate(at)
        return true
    }

    override fun clearLineFocus() {
        if (focus == null) return
        focus = null
        content.invalidate()
    }

    /** Scrolls [line] into the list's window. */
    private fun show(line: Int) {
        noticeScroll(line, scrollY, height)?.let { smoothScrollTo(0, it) }
    }

    // The lines draw only what shows (Lines.onDraw): scrolled, they are drawn again.
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        content.invalidate()
    }

    // The home does not clip its children (HomeView: the moved icon goes over everything) and a
    // ScrollView without padding does not clip its own: scrolled, the lines drew over the banner.
    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(scrollX, scrollY, scrollX + width, scrollY + height)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    private fun activate(line: Int) {
        when (val l = lines.getOrNull(line)) {
            is NoticeHeader -> l.app?.let(opener::openApp)
            is NoticeRow -> opener.open(l.item, l.sender) { if (it != Opened.UNAVAILABLE) markRead(l.item) }
            null -> close()
        }
    }

    /** The lines, drawn on one canvas from strings cut once per read. */
    private inner class Lines : View(context) {
        var strip: Bitmap? = null
        private val title = textPaint(Typography.TEXT, Typography.REGULAR, NoticeColors.Title)
        private val text = TextPaint(textPaint(Typography.TEXT, Typography.REGULAR, NoticeColors.Text))
        private val focused = textPaint(Typography.TEXT, Typography.REGULAR, NoticeColors.Title)
        private val divider = fillPaint(NoticeColors.Divider)
        private val band = focusPaint(D.LINE)
        private val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = Rect()
        private val dst = Rect()
        // Per line: header title or time, cut to its column.
        private var cut: Array<Array<String>> = emptyArray()
        // The senders' and summaries' emojis as OS 7's emoticons, by line; null for a header.
        private var summaries: Array<List<Chunk>?> = emptyArray()
        private var senders: Array<List<Chunk>?> = emptyArray()
        // Per line: an app header's own icon, in place of the counters' "?" bubble (D: an OS 7
        // app showed its own indicator); null for the counters' sections and for rows.
        private var icons: Array<Bitmap?> = emptyArray()
        private val pen = EmoticonPen()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downY = 0f
        private var downX = 0f

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun build() {
            cut = Array(lines.size) {
                when (val l = lines[it]) {
                    is NoticeHeader -> arrayOf(title.fit(l.title, (D.SUMMARY_RIGHT - D.TITLE_X).toFloat()))
                    is NoticeRow -> arrayOf(l.time)
                }
            }
            summaries = Array(lines.size) {
                (lines[it] as? NoticeRow)?.let { l -> emoticonChunks(text.fitEmoticons(l.summary, (D.SUMMARY_RIGHT - D.SUMMARY_X).toFloat())) }
                    ?.also { c -> EmoticonStrip.want(this, c) }
            }
            icons = Array(lines.size) { (lines[it] as? NoticeHeader)?.takeIf { h -> h.frame == UNREAD_OTHER }?.app?.let(appIcon) }
            senders = Array(lines.size) {
                (lines[it] as? NoticeRow)?.let { l -> emoticonChunks(text.fitEmoticons(l.sender, D.SENDER_WIDTH.toFloat())) }
                    ?.also { c -> EmoticonStrip.want(this, c) }
            }
            requestLayout()
            invalidate()
        }

        // At least the window's height when the ScrollView fills it (isFillViewport).
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val h = D.GAP + lines.size * D.LINE
            val exact = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), if (exact) maxOf(h, MeasureSpec.getSize(heightSpec)) else h)
        }

        override fun onDraw(canvas: Canvas) {
            // The list's scroll, not this view's own (always 0).
            val scroll = this@NoticesView.scrollY
            val window = this@NoticesView.height
            for (i in lines.indices) {
                if (!noticeShown(i, scroll, window)) continue
                val top = D.GAP + i * D.LINE
                val lit = i == focus
                if (lit) {
                    canvas.save()
                    canvas.translate(0f, top.toFloat())
                    canvas.drawRect(0f, 0f, width.toFloat(), D.LINE.toFloat(), band)
                    canvas.restore()
                }
                val s = cut[i]
                when (val l = lines[i]) {
                    is NoticeHeader -> {
                        if (!lit) canvas.drawRect(0f, (top + D.LINE - 1).toFloat(), width.toFloat(), (top + D.LINE).toFloat(), divider)
                        val own = icons[i]
                        if (own != null) drawIcon(canvas, own, D.ICON_X, top + D.ICON_Y) else strip?.let { drawFrame(canvas, it, l.frame, D.ICON_X, top + D.ICON_Y) }
                        canvas.drawText(s[0], D.TITLE_X.toFloat(), (top + D.TITLE_BASELINE).toFloat(), title)
                    }
                    is NoticeRow -> {
                        val paint = if (lit) focused else text
                        val y = (top + D.ROW_BASELINE).toFloat()
                        canvas.drawText(s[0], D.TIME_X.toFloat(), y, paint)
                        pen.draw(canvas, senders[i] ?: emptyList(), D.SENDER_X.toFloat(), y, paint)
                        pen.draw(canvas, summaries[i] ?: emptyList(), D.SUMMARY_X.toFloat(), y, paint)
                    }
                }
            }
        }

        /** Frame [f] of the counters' strip (33x29 frames, the banner's), at ([x], [y]). */
        private fun drawFrame(canvas: Canvas, strip: Bitmap, f: Int, x: Int, y: Int) {
            src.set(f * 33, 0, (f + 1) * 33, 29)
            dst.set(x, y, x + 33, y + 29)
            canvas.drawBitmap(strip, src, dst, bitmap)
        }

        /**
         * An app's 92 px icon in the 33x29 place of a frame: 29 px square, centred, without the
         * 12 px of margin and shadow round an OS 7 icon's image (D).
         */
        private fun drawIcon(canvas: Canvas, icon: Bitmap, x: Int, y: Int) {
            val m = icon.width * 12 / 92
            src.set(m, m, icon.width - m, icon.height - m)
            dst.set(x + 2, y, x + 31, y + 29)
            canvas.drawBitmap(icon, src, dst, bitmap)
        }

        // A tap: the line under it, or under the lines the list closes. A drag is the scroll's.
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    clearLineFocus()
                }
                MotionEvent.ACTION_UP -> if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                    val at = ((e.y - D.GAP) / D.LINE).toInt()
                    if (e.y < D.GAP || at >= lines.size) close() else activate(at)
                }
            }
            return true
        }
    }
}
