package dev.bold7.launcher.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import dev.bold7.design.Curves
import dev.bold7.design.Durations
import dev.bold7.design.HomeColors
import dev.bold7.design.HomeDimens
import dev.bold7.design.Typography
import dev.bold7.design.toInterpolator
import dev.bold7.ui.fit
import dev.bold7.ui.typeface

/** The tab's images of the theme (home_*.png, A). */
internal class TabImages(val lineTop: Bitmap, val lineBottom: Bitmap, val focus: FocusPatch, val mask: Bitmap)

/**
 * The room the tab's texts get, pure (TabRoomTest): a view's name sits between its neighbours' (half a
 * width apart), an app's name between the edges the mask darkens, [EDGE] px off each.
 */
object TabRoom {
    private const val EDGE = 40
    private const val AIR = 20

    fun label(width: Int): Float = (width / 2f - AIR).coerceAtLeast(0f)
    fun name(width: Int): Float = (width - 2f * EDGE).coerceAtLeast(0f)
}

/**
 * The tab of the tray's views (sim-02, sim-07, sim-11 to sim-14, C), 720x46: the view's name
 * centred, its neighbours' names centred on the edges, cut by them and darkened by the theme's
 * mask (homescreen_mask_p.png, its two halves at the edges, D for the square), the theme's lines
 * above and under it. Changing view slides the names a width round in 300 ms
 * (animation-duration-tabmanager-portrait, QUADRATIC_OUT, A). The focus is the home's icon focus
 * stretched to the whole width (sim-07, sim-72). An app's name ([showName]) fades in over the view's for 3 s
 * (tab-label-animation-*, A; sim-08).
 */
internal class TabView(context: Context, private val label: (Panel) -> String) : View(context) {
    private var images: TabImages? = null
    private var panel = Panel.ALL
    private var name = ""
    private var shift = 0f // the names' offset while sliding, px
    private var nameAlpha = 0f
    var focused = false
        set(value) {
            if (field != value) invalidate()
            field = value
        }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typography.Banner.typeface()
        textSize = Typography.Banner.sizePx.toFloat()
        color = HomeColors.Text
        textAlign = Paint.Align.CENTER
    }
    private val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val focusRect = RectF()
    private val labels = Panel.entries.associateWith { label(it) }

    // The texts cut to their room (TabRoom, fit of core/ui), worked out when a name or the width
    // changes, never in a frame.
    private var shownLabels = labels
    private var shownName = ""

    private fun refit() {
        shownLabels = labels.mapValues { text.fit(it.value, TabRoom.label(width)) }
        shownName = text.fit(name, TabRoom.name(width))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refit()

    private val slide = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = Durations.TRANSITION_MS
        interpolator = Curves.QuadraticOut.toInterpolator()
        addUpdateListener { shift = from * (1f - it.animatedFraction); invalidate() }
    }
    private var from = 0f

    // The app's name fades to [to] from where it is: a new name in the middle of a fade goes on from there.
    private val fade = ValueAnimator().apply {
        duration = Durations.TAB_LABEL_FADE_MS
        addUpdateListener { nameAlpha = it.animatedValue as Float; invalidate() }
    }
    private val fadeOut = Runnable { fadeTo(0f) }

    private fun fadeTo(to: Float) {
        fade.cancel()
        if (nameAlpha == to) return
        fade.setFloatValues(nameAlpha, to)
        fade.start()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun images(images: TabImages) {
        this.images = images
        invalidate()
    }

    private var hidden = emptySet<Panel>()

    /** The views Manage Panels hides: the names round the tab are the others'. */
    fun hidden(views: Set<Panel>) {
        if (views == hidden) return
        hidden = views
        invalidate()
    }

    /** Shows [next], its name centred; [slideIn] or [setShift] move the names. */
    fun show(next: Panel) {
        if (next == panel) return
        slide.cancel()
        panel = next
        shift = 0f
        invalidate()
    }

    /** The names slide half a width in from the side the view came ([step] 1: from the right), as the trackpad turns it. */
    fun slideIn(step: Int) {
        from = step * width / 2f
        shift = from
        if (isAttachedToWindow) slide.start() else shift = 0f
        invalidate()
    }

    /** The names [px] off centre, under the finger or its spring (names half a width apart). */
    fun setShift(px: Float) {
        slide.cancel()
        shift = px
        invalidate()
    }

    /** [app]'s name over the view's for 3 s, or none now (null). */
    fun showName(app: String?) {
        removeCallbacks(fadeOut)
        if (app == null) {
            fadeTo(0f)
            return
        }
        // Drawn now, faded in or not: a name fully shown was replaced without a frame, the tab
        // naming the icon of a step before (seen as Calendar over Camera).
        name = app
        shownName = text.fit(app, TabRoom.name(width))
        invalidate()
        fadeTo(1f)
        postDelayed(fadeOut, Durations.TAB_LABEL_HOLD_MS)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), HomeDimens.TAB)

    override fun onDraw(canvas: Canvas) {
        val img = images ?: return
        val w = width
        val h = height
        // The icon focus over the whole width, 43 rows from the tab's third, under the edges'
        // mask (sim-72, C); not tab~focus.png, the dark band of the apps' tabs.
        if (focused) {
            focusRect.set(0f, FOCUS_TOP, w.toFloat(), FOCUS_TOP + FOCUS_HEIGHT)
            img.focus.draw(canvas, focusRect)
        }
        val base = h / 2f - (text.ascent() + text.descent()) / 2
        val viewsAlpha = ((1f - nameAlpha) * 255).toInt()
        text.alpha = viewsAlpha
        if (viewsAlpha > 0) {
            val centre = w / 2f + shift
            // A view alone has no neighbours (D: Manage Panels leaving All only).
            val around = if (panel.next(1, hidden) == panel) 0 else 2
            for (k in -around..around) canvas.drawText(shownLabels.getValue(panel.next(k, hidden)), centre + k * w / 2f, base, text)
        }
        if (nameAlpha > 0f) {
            text.alpha = (nameAlpha * 255).toInt()
            canvas.drawText(shownName, w / 2f, base, text)
        }
        // The edges darken what goes past them; the lines on top.
        val half = img.mask.width / 2
        src.set(0, 0, half, img.mask.height)
        dst.set(0, 0, half, h)
        canvas.drawBitmap(img.mask, src, dst, bitmap)
        src.set(half, 0, img.mask.width, img.mask.height)
        dst.set(w - half, 0, w, h)
        canvas.drawBitmap(img.mask, src, dst, bitmap)
        wide(canvas, img.lineTop, 0, img.lineTop.height)
        wide(canvas, img.lineBottom, h - img.lineBottom.height, h)
    }

    private fun wide(canvas: Canvas, image: Bitmap, top: Int, bottom: Int) {
        src.set(0, 0, image.width, image.height)
        dst.set(0, top, width, bottom)
        canvas.drawBitmap(image, src, dst, bitmap)
    }

    private companion object {
        const val FOCUS_TOP = 2f // sim-72: the tab y 140 to 185, its focus y 142 to 184 (C)
        const val FOCUS_HEIGHT = 43f
    }

    override fun onDetachedFromWindow() {
        slide.cancel()
        fade.cancel()
        removeCallbacks(fadeOut)
        super.onDetachedFromWindow()
    }
}
