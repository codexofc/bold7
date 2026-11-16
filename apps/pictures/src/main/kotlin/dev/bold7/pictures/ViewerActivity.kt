package dev.bold7.pictures

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import dev.bold7.design.Typography
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.status
import dev.bold7.ui.textPaint
import kotlin.math.abs

/**
 * A picture in full (docs/BBOS7-VISUEL.md §37.3): black, without the status bar (sim-353, C), the
 * picture fitted; the click zooms by 1.18 (six times at most), Escape zooms out then closes, the
 * trackpad pans when zoomed and goes to the previous or the next picture when not (sim-358 to
 * sim-361, C; guide 7.1 p. 153, B); I, O, 2, 4, 6, 8 and L (guide p. 55, B). The category's name
 * and "2 of 14" show over the top (sim-364, C) and, zoomed, the frame of the whole picture with the
 * part shown (sim-363, C), each for a moment. View Slide Show turns the pictures by itself; Escape
 * closes it (guide p. 154, B). Rotate turns the picture on the screen only (D).
 */
class ViewerActivity : Activity() {
    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_BUCKET = "bucket"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_SLIDESHOW = "slideshow"

        /** How long the name and the zoom's frame stay (eyeballed, unsourced: gone in the captures taken 3 s after). */
        const val OVERLAY_MS = 2000L

        /** A slide's time (D: the guide names Slide Show Interval, p. 154, without its default). */
        const val SLIDE_MS = 3000L

        /** The longer side a picture is decoded at: twice the Q25's screen (D), the zoom's 2.7 times stays sharp enough, the memory bounded. */
        const val DECODE_MAX = 1440

        fun intent(context: Context, source: Source, title: String, index: Int, slideshow: Boolean): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_KIND, when (source) { Source.Camera -> 0; Source.Library -> 1; is Source.Folder -> 2 })
                .putExtra(EXTRA_BUCKET, (source as? Source.Folder)?.bucketId ?: 0L)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_SLIDESHOW, slideshow)
    }

    private lateinit var picture: PictureView
    private lateinit var menuView: MenuView<Act>
    private lateinit var acts: PictureActs
    private lateinit var source: Source
    private var titleText = ""
    private var pictures: List<Picture> = emptyList()
    private var viewing = Viewing(0, 0)
    private var menu: Menu<Act>? = null
    private var held = false
    private var menuClick = false
    private var slideshow = false
    private var decoding = -1L
    private val glyphs = HashMap<Act, Bitmap>()

    private val slide = object : Runnable {
        override fun run() {
            if (!slideshow || pictures.isEmpty()) return
            show(if (viewing.index + 1 < pictures.size) Viewing(viewing.index + 1, pictures.size) else Viewing(0, pictures.size))
            picture.postDelayed(this, SLIDE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = when (intent.getIntExtra(EXTRA_KIND, 1)) {
            0 -> Source.Camera
            2 -> Source.Folder(intent.getLongExtra(EXTRA_BUCKET, 0))
            else -> Source.Library
        }
        titleText = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        slideshow = intent.getBooleanExtra(EXTRA_SLIDESHOW, false)
        acts = PictureActs(this) { load() }
        picture = PictureView(this)
        menuView = MenuView(this, glyph = { glyphs[it] }, label = ::label, onEntry = ::chosen, onExpand = { menu?.let { open(it.expand()) } }, onClose = { open(null) })
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(picture)
            addView(menuView)
        })
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        val g = graph
        g.io.post {
            val images = mapOf(
                Act.VIEW_SLIDESHOW to R.drawable.pic_sm_play, Act.SET_WALLPAPER to R.drawable.pic_sm_set_as_wallpaper,
                Act.ASSIGN_CONTACT to R.drawable.pic_sm_set_as_caller_id, Act.SEND to R.drawable.pic_sm_send_or_share_image,
                Act.DELETE to R.drawable.pic_sm_delete, Act.ZOOM to R.drawable.pic_sm_zoom_in, Act.ROTATE to R.drawable.pic_sm_rotate,
            ).mapValues { BitmapFactory.decodeResource(resources, it.value, BitmapFactory.Options().apply { inScaled = false }) }
            runOnUiThread { glyphs.putAll(images) }
        }
        load(intent.getIntExtra(EXTRA_INDEX, 0))
    }

    override fun onDestroy() {
        picture.removeCallbacks(slide)
        super.onDestroy()
    }

    /** The category read again off the main thread; the picture at [index] (or the one shown, or the next after a delete). */
    private fun load(index: Int = viewing.index) {
        val g = graph
        g.io.post {
            val list = picturesOf(g.all(), source, Sort.NAME)
            runOnUiThread {
                pictures = list
                if (list.isEmpty()) return@runOnUiThread finish()
                show(Viewing(index.coerceIn(0, list.size - 1), list.size))
                if (slideshow) {
                    picture.removeCallbacks(slide)
                    picture.postDelayed(slide, SLIDE_MS)
                }
            }
        }
    }

    /** Shows [v], decoding its picture off the main thread when it changed. */
    private fun show(v: Viewing) {
        val changed = v.index != viewing.index || picture.bitmap == null || pictures.getOrNull(v.index)?.id != decoding
        viewing = v
        picture.header(titleText, v.position)
        val p = pictures.getOrNull(v.index) ?: return
        if (!changed) return picture.turn(v.quarter)
        decoding = p.id
        val g = graph
        g.decoder.execute {
            val b = g.store.picture(p, DECODE_MAX)
            g.main.post {
                if (decoding != p.id) return@post
                if (b == null) status(this, getString(R.string.unreadable)) else picture.show(b, viewing.quarter)
            }
        }
    }

    private fun current(): Picture? = pictures.getOrNull(viewing.index)

    private fun back() {
        if (slideshow) return finish()
        if (!picture.zoomOut()) finish() else if (picture.fitted) picture.header(titleText, viewing.position)
    }

    private fun step(forward: Boolean) {
        val next = viewing.step(forward)
        if (next != viewing) show(next)
    }

    private fun move(dx: Int, dy: Int) {
        if (picture.fitted) {
            if (dx != 0) step(dx > 0)
        } else {
            picture.pan(dx, dy)
        }
    }

    private fun act(a: Act) {
        val p = current()
        when (a) {
            Act.VIEW_SLIDESHOW -> {
                slideshow = true
                picture.removeCallbacks(slide)
                picture.postDelayed(slide, SLIDE_MS)
            }
            Act.DELETE -> p?.let(acts::delete)
            Act.SET_WALLPAPER -> p?.let(acts::setWallpaper)
            Act.ASSIGN_CONTACT -> p?.let(acts::assignToContact)
            Act.SEND -> open(sendMenu())
            Act.SEND_TEXT -> p?.let(acts::sendAsText)
            Act.ROTATE -> {
                viewing = viewing.rotate()
                picture.turn(viewing.quarter)
            }
            Act.ZOOM -> picture.zoomIn()
            Act.CLOSE -> finish()
            Act.SWITCH -> SwitchApplication.start(this)
            Act.OPEN -> Unit
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!acts.onResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    // Menus

    private fun label(a: Act) = getString(when (a) {
        Act.VIEW_SLIDESHOW -> R.string.act_view_slideshow
        Act.DELETE -> R.string.act_delete
        Act.SET_WALLPAPER -> R.string.act_set_wallpaper
        Act.ASSIGN_CONTACT -> R.string.act_assign_contact
        Act.SEND -> if (menu?.short == true) R.string.act_send_short else R.string.act_send
        Act.SEND_TEXT -> R.string.act_send_text
        Act.ROTATE -> R.string.act_rotate
        Act.ZOOM -> R.string.act_zoom
        Act.CLOSE -> R.string.act_close
        Act.SWITCH -> R.string.act_switch_application
        Act.OPEN -> R.string.act_open
    })

    private fun open(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        if (a != Act.SEND) open(null)
        act(a)
    }

    private fun shortMenu() = viewerShortMenu(pictures.size)

    // Keys and touch

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            if (held || menuClick) { held = false; menuClick = false } else if (menu == null && !slideshow) picture.zoomIn()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            // The click's release then belongs to the menu, not to the screen under it (it would open or zoom).
            if (click) menuClick = true
            when (val r = m.key(
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                    KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                    else -> if (click) MenuKey.CLICK else return true
                },
            )) {
                is MenuResult.Show -> open(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> open(null)
            }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> back()
            KeyEvent.KEYCODE_MENU -> open(viewerMenu(pictures.size))
            else -> when {
                click -> if (e.isLongPress || e.repeatCount == 1) {
                    held = true
                    open(shortMenu())
                }
                // The Pictures shortcuts (guide 7.1 p. 55, B), by the character the key gives.
                else -> when (e.unicodeChar.toChar().lowercaseChar()) {
                    'i' -> picture.zoomIn()
                    'o' -> back()
                    '2' -> picture.pan(0, -1)
                    '8' -> picture.pan(0, 1)
                    '4' -> picture.pan(-1, 0)
                    '6' -> picture.pan(1, 0)
                    'l' -> act(Act.ROTATE)
                    else -> return super.dispatchKeyEvent(e)
                }
            }
        }
        return true
    }

    /**
     * The picture at its [Zoom], turned, on black; over it for [OVERLAY_MS] the category's name and
     * the position (a band of 80 px, black at 59 %, two white lines of 27 px centred on baselines 34
     * and 66, sim-364, C) and, zoomed, the zoom's frame (128x96, 16 px from the bottom right corner,
     * the whole picture dimmed, the part shown lighter, sim-363, C; its greys eyeballed, unsourced). A finger
     * drags a zoomed picture, flings a fitted one to the next (guide p. 153, M), holds for the short menu.
     */
    private inner class PictureView(context: Context) : View(context) {
        var bitmap: Bitmap? = null
            private set
        private var zoom: Zoom? = null
        private var quarter = 0
        private val matrix = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val band = fillPaint(0x96000000.toInt())
        private val text = textPaint(Typography.TEXT, Typography.REGULAR, Color.WHITE, align = Paint.Align.CENTER)
        private val frameLine = Paint().apply { color = 0xFF404040.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        private val dim = fillPaint(0xB4000000.toInt())
        private val part = fillPaint(0x78FFFFFF)
        private val box = RectF()
        private val mini = Matrix()
        private var line1 = ""
        private var line2 = ""
        private var headerUntil = 0L
        private var frameUntil = 0L

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (fitted) return false
                update { z, f -> z.drag(dx, dy, f) }
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (fitted && abs(vx) > abs(vy)) step(vx < 0)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (menu == null) open(shortMenu())
            }
        })

        val fitted: Boolean get() = (zoom?.level ?: 0) == 0

        private fun frame(): Frame? = bitmap?.takeIf { width > 0 }?.let { Frame(it.width, it.height, width, height, quarter) }

        fun show(b: Bitmap, turns: Int) {
            bitmap = b
            quarter = turns
            zoom = frame()?.let(Zoom::fit)
            invalidate()
        }

        fun turn(turns: Int) {
            if (turns == quarter) return
            quarter = turns
            zoom = frame()?.let(Zoom::fit)
            invalidate()
        }

        fun header(title: String, position: String) {
            line1 = title
            line2 = position
            headerUntil = System.currentTimeMillis() + OVERLAY_MS
            postInvalidateDelayed(OVERLAY_MS)
            invalidate()
        }

        fun zoomIn() = update { z, f -> z.zoomIn(f) }

        /** False when it was fitted: the viewer closes. */
        fun zoomOut(): Boolean {
            val f = frame() ?: return false
            zoom = zoom?.zoomOut(f) ?: return false
            framed()
            return true
        }

        fun pan(dx: Int, dy: Int) = update { z, f -> z.pan(dx, dy, f) }

        private inline fun update(change: (Zoom, Frame) -> Zoom) {
            val f = frame() ?: return
            zoom = change(zoom ?: Zoom.fit(f), f)
            framed()
        }

        private fun framed() {
            frameUntil = if (fitted) 0L else System.currentTimeMillis() + OVERLAY_MS
            postInvalidateDelayed(OVERLAY_MS)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            zoom = frame()?.let { f -> zoom?.pan(0, 0, f) ?: Zoom.fit(f) }
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = gestures.onTouchEvent(e)

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            val f = frame() ?: return
            val z = zoom ?: return
            val s = z.scale(f)
            // The bitmap turned about its centre into the turned picture's space, then zoomed around z.
            matrix.setTranslate(-b.width / 2f, -b.height / 2f)
            matrix.postRotate(90f * quarter)
            matrix.postTranslate(f.shownWidth / 2f - z.x, f.shownHeight / 2f - z.y)
            matrix.postScale(s, s)
            matrix.postTranslate(width / 2f, height / 2f)
            canvas.drawBitmap(b, matrix, paint)
            val now = System.currentTimeMillis()
            if (now < frameUntil && !fitted) drawFrame(canvas, f, z, s, b)
            if (now < headerUntil && fitted) {
                canvas.drawRect(0f, 0f, width.toFloat(), 80f, band)
                canvas.drawText(text.fit(line1, width - 20f), width / 2f, 34f, text)
                canvas.drawText(line2, width / 2f, 66f, text)
            }
        }

        private fun drawFrame(canvas: Canvas, f: Frame, z: Zoom, s: Float, b: Bitmap) {
            val right = width - 16f
            val bottom = height - 16f
            val w = 128f
            val h = 96f
            box.set(right - w, bottom - h, right, bottom)
            // The whole picture fitted into the frame, dimmed; the part the screen shows, lighter.
            val k = minOf(w / f.shownWidth, h / f.shownHeight)
            val left = box.left + (w - f.shownWidth * k) / 2
            val top = box.top + (h - f.shownHeight * k) / 2
            val save = canvas.save()
            canvas.clipRect(box)
            mini.setTranslate(-b.width / 2f, -b.height / 2f)
            mini.postRotate(90f * quarter)
            mini.postTranslate(f.shownWidth / 2f, f.shownHeight / 2f)
            mini.postScale(k, k)
            mini.postTranslate(left, top)
            canvas.drawBitmap(b, mini, paint)
            canvas.drawRect(box, dim)
            val halfW = width / 2f / s
            val halfH = height / 2f / s
            canvas.drawRect(left + (z.x - halfW).coerceAtLeast(0f) * k, top + (z.y - halfH).coerceAtLeast(0f) * k,
                left + (z.x + halfW).coerceAtMost(f.shownWidth.toFloat()) * k, top + (z.y + halfH).coerceAtMost(f.shownHeight.toFloat()) * k, part)
            canvas.restoreToCount(save)
            canvas.drawRect(box.left - 0.5f, box.top - 0.5f, box.right + 0.5f, box.bottom + 0.5f, frameLine)
        }
    }
}
