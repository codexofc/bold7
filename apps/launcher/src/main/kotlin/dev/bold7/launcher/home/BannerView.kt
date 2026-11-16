package dev.bold7.launcher.home

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.NinePatch
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.HomeBanner
import android.view.ViewConfiguration
import dev.bold7.design.HomeColors
import dev.bold7.design.HomeDimens
import dev.bold7.design.TextToken
import dev.bold7.design.Typography
import dev.bold7.launcher.R
import dev.bold7.ui.fit
import dev.bold7.ui.typeface

/** The banner's images of the theme (home_*.png, A), decoded on io. */
internal class BannerImages(private val res: Resources) {
    private fun decode(id: Int): Bitmap = BitmapFactory.decodeResource(res, id)
    val background = decode(R.drawable.home_banner_background)
    val backgroundFocus = decode(R.drawable.home_banner_background_focus)
    val divider = decode(R.drawable.home_banner_divider)
    val row = decode(R.drawable.home_indicator_bar_background)
    val battery = decode(R.drawable.home_net_rim_indicator_batterylevel_icons50x25)
    val signal = decode(R.drawable.home_net_rim_ribbon_signallevel_icons49x19)
    val wifi = decode(R.drawable.home_net_rim_ribbon_wlansignallevel_icons30x24)
    val profile = decode(R.drawable.home_net_rim_ribbon_profile_icons43x43)
    val unread = decode(R.drawable.home_net_rim_ribbon_unreadcount_icons33x29)
    val search = decode(R.drawable.home_banner_search_button)
    val alarm = decode(R.drawable.home_net_rim_alarm_indicator_icons21x21)
    /** The home's one trackpad focus but the banner's: the speaker, the notifications, the magnifier, the tab, an icon. */
    val focus = decode(R.drawable.home_focus).let { NinePatch(it, it.ninePatchChunk).apply { paint = Paint(Paint.FILTER_BITMAP_FLAG) } }
}

/**
 * The home banner and its indicator row (docs/BBOS7-VISUEL.md §1), 720x140, drawn on its canvas:
 * the theme's images at their size, the backgrounds widened (their columns are all alike), the
 * places of sim-03 (C) with what is anchored right moved 80 px (D). It renders [state] and
 * [focus]; a tap on a target goes to [onTap]. Nothing is allocated in onDraw.
 */
internal class BannerView(context: Context, private val onTap: (BannerFocus) -> Unit, private val onPull: () -> Unit) : View(context) {
    private var images: BannerImages? = null
    var state = BannerState()
        set(value) {
            if (field != value) {
                field = value
                refit()
                invalidate()
            }
        }

    // The texts cut to their room (BannerRoom, fit of core/ui): worked out when the state or the
    // width changes, never in a frame.
    private var date = ""
    private var network = ""
    private var coverage = ""

    private fun refit() {
        val s = state
        date = datePaint.fit(s.date, BannerRoom.date(width))
        network = networkPaint.fit(s.network, BannerRoom.network(width))
        coverage = coveragePaint.fit(s.coverage, BannerRoom.coverage(width))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refit()
    var focus: BannerFocus? = null
        set(value) {
            if (field != value) invalidate()
            field = value
        }

    private val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stretch = Paint() // nearest: the stretched images are uniform along x
    private val src = Rect()
    private val dst = Rect()
    private val dstF = RectF()

    private fun text(token: TextToken, color: Int, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = token.typeface()
        textSize = token.sizePx.toFloat()
        this.color = color
        textAlign = align
    }

    private val datePaint = text(Typography.Banner, HomeColors.Text, Paint.Align.LEFT)
    private val networkPaint = text(Typography.Banner, HomeColors.Text, Paint.Align.CENTER)
    private val coveragePaint = text(Typography.Banner, HomeColors.Text, Paint.Align.RIGHT)
    private val countPaint = text(Typography.Banner, HomeColors.Text, Paint.Align.LEFT)
    private val timePaint = text(Typography.BannerTime, HomeColors.BannerTime, Paint.Align.CENTER)
    private val amPmPaint = text(Typography.Banner.copy(sizePx = Typography.BANNER_AM_PM), HomeColors.BannerTime, Paint.Align.LEFT)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun images(images: BannerImages) {
        this.images = images
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), HomeDimens.BANNER + HomeDimens.INDICATOR_ROW)

    override fun onDraw(canvas: Canvas) {
        val img = images ?: return
        val w = width
        val s = state
        val rowTop = HomeDimens.BANNER
        wide(canvas, if (focus == BannerFocus.BANNER) img.backgroundFocus else img.background, 0)
        wide(canvas, img.divider, rowTop - 1)
        wide(canvas, img.row, rowTop)
        // The banner (sim-03, C): battery top left, date under it, network and time centred, coverage
        // and signal top right, Wi-Fi under them.
        frame(canvas, img.battery, 50, 25, s.battery, BATTERY_X, BATTERY_Y)
        if (s.alarm) canvas.drawBitmap(img.alarm, HomeBanner.ALARM_X.toFloat(), HomeBanner.ALARM_Y.toFloat(), bitmap)
        canvas.drawText(date, DATE_X, DATE_BASELINE, datePaint)
        canvas.drawText(network, w / 2f, NETWORK_BASELINE, networkPaint)
        canvas.drawText(s.time, w / 2f, TIME_BASELINE, timePaint)
        if (s.amPm.isNotEmpty()) canvas.drawText(s.amPm, w / 2f + timePaint.measureText(s.time) / 2 + AM_PM_GAP, TIME_BASELINE, amPmPaint)
        canvas.drawText(coverage, (w - COVERAGE_RIGHT).toFloat(), NETWORK_BASELINE, coveragePaint)
        frame(canvas, img.signal, 49, 19, s.signal, w - SIGNAL_RIGHT, SIGNAL_Y)
        s.wifi?.let { frame(canvas, img.wifi, 30, 24, it, w - WIFI_RIGHT, WIFI_Y) }
        // The row (sim-03, sim-25, C): speaker left, counts centred, magnifier right; the focus
        // under what it is on, the icon focus with its corners (sim-79, sim-81, C: speaker 45x45 from
        // y 94, notifications x 45 to 594 from y 93, 46 rows, magnifier 45x45 at the right edge).
        when (focus) {
            BannerFocus.SPEAKER -> under(canvas, img.focus, 0f, rowTop + 1f, FOCUS_SQUARE.toFloat(), rowTop + 1f + FOCUS_SQUARE)
            BannerFocus.SEARCH -> under(canvas, img.focus, w - FOCUS_SQUARE.toFloat(), rowTop + 1f, w.toFloat(), rowTop + 1f + FOCUS_SQUARE)
            BannerFocus.NOTIFICATIONS -> under(canvas, img.focus, FOCUS_SQUARE.toFloat(), rowTop.toFloat(), (w - FOCUS_SQUARE).toFloat(), rowTop + FOCUS_SQUARE + 1f)
            else -> Unit
        }
        frame(canvas, img.profile, 43, 43, s.profile, PROFILE_X, rowTop + ROW_ICON_Y)
        canvas.drawBitmap(img.search, (w - SEARCH_RIGHT).toFloat(), (rowTop + ROW_ICON_Y).toFloat(), bitmap)
        // By index: no iterator in a frame.
        val list = s.indicators
        var x = (w - indicatorsWidth(list)) / 2f
        for (k in list.indices) {
            val i = list[k]
            val n = digits(i.count)
            canvas.drawText(n, x, (rowTop + COUNT_BASELINE).toFloat(), countPaint)
            x += countPaint.measureText(n) + COUNT_GAP
            frame(canvas, img.unread, 33, 29, i.strip, x.toInt(), rowTop + UNREAD_Y)
            x += 33 + INDICATOR_GAP
        }
    }

    private fun indicatorsWidth(list: List<Indicator>): Float {
        var total = 0f
        for (k in list.indices) total += countPaint.measureText(digits(list[k].count)) + COUNT_GAP + 33 + INDICATOR_GAP
        return (total - INDICATOR_GAP).coerceAtLeast(0f)
    }

    // The counts' strings, made once each: none in the frames that follow.
    private val counts = HashMap<Int, String>()
    private fun digits(n: Int) = counts.getOrPut(n) { n.toString() }

    /** A 640 px wide image of uniform columns across the whole width, at [top]. */
    private fun wide(canvas: Canvas, image: Bitmap, top: Int) {
        src.set(0, 0, image.width, image.height)
        dst.set(0, top, width, top + image.height)
        canvas.drawBitmap(image, src, dst, stretch)
    }

    /** Frame [f] of a strip of [fw]x[fh] frames, at ([x], [y]), its size. */
    private fun frame(canvas: Canvas, strip: Bitmap, fw: Int, fh: Int, f: Frame, x: Int, y: Int) {
        src.set(f.column * fw, f.row * fh, (f.column + 1) * fw, (f.row + 1) * fh)
        dst.set(x, y, x + fw, y + fh)
        canvas.drawBitmap(strip, src, dst, bitmap)
    }

    private fun under(canvas: Canvas, image: NinePatch, l: Float, t: Float, r: Float, b: Float) {
        dstF.set(l, t, r, b)
        image.draw(canvas, dstF)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var pulled = false

    // A tap on a target, or a pull down from anywhere on the banner (bannerPulled), at once.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                pulled = false
            }
            MotionEvent.ACTION_MOVE -> if (!pulled && bannerPulled(e.x - downX, e.y - downY, slop)) {
                pulled = true
                onPull()
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP && !pulled) {
            val rowTop = HomeDimens.BANNER
            onTap(
                when {
                    e.y < rowTop -> BannerFocus.BANNER
                    e.x < FOCUS_SQUARE -> BannerFocus.SPEAKER
                    e.x > width - FOCUS_SQUARE -> BannerFocus.SEARCH
                    else -> BannerFocus.NOTIFICATIONS
                },
            )
        }
        return true
    }

    private companion object {
        const val BATTERY_X = HomeBanner.BATTERY_X
        const val BATTERY_Y = HomeBanner.BATTERY_Y
        const val DATE_X = HomeBanner.DATE_X.toFloat()
        const val DATE_BASELINE = HomeBanner.DATE_BASELINE.toFloat()
        const val NETWORK_BASELINE = HomeBanner.NETWORK_BASELINE.toFloat()
        const val TIME_BASELINE = HomeBanner.TIME_BASELINE.toFloat()
        const val AM_PM_GAP = HomeDimens.AM_PM_GAP
        const val COVERAGE_RIGHT = HomeBanner.COVERAGE_RIGHT
        const val SIGNAL_RIGHT = HomeBanner.SIGNAL_RIGHT
        const val SIGNAL_Y = HomeBanner.SIGNAL_Y
        const val WIFI_RIGHT = HomeBanner.WIFI_RIGHT
        const val WIFI_Y = HomeBanner.WIFI_Y
        const val PROFILE_X = HomeBanner.PROFILE_X
        const val ROW_ICON_Y = HomeBanner.ROW_ICON_Y
        const val SEARCH_RIGHT = HomeBanner.SEARCH_RIGHT
        const val COUNT_BASELINE = HomeBanner.COUNT_BASELINE
        const val COUNT_GAP = HomeBanner.COUNT_GAP
        const val UNREAD_Y = HomeBanner.UNREAD_Y
        const val INDICATOR_GAP = HomeBanner.INDICATOR_GAP
        const val FOCUS_SQUARE = HomeDimens.BANNER_FOCUS_SQUARE // sim-79, sim-81: the speaker x 0 to 44, the magnifier from 595 (C)
    }
}
