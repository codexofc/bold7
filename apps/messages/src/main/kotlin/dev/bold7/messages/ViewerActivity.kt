package dev.bold7.messages

import android.app.Activity
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import dev.bold7.ui.Dir
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.Travel
import dev.bold7.ui.status
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The viewer's menu: Save Image ("Open a picture attachment in a text message … Save Image > Save", guide 7.1 p. 153, B) and Zoom (ps 33262, 33263, C). */
enum class ViewerAct { SAVE_IMAGE, ZOOM }

/**
 * A text's picture in full: the whole screen in black, without the status bar, as
 * the 9900's picture viewer (ps 33262, 33263, C), the picture fitted then zoomed by the click and
 * out by Escape, panned by the trackpad, I, O, 2, 4, 6, 8 (guide 7.1 p. 38 and 153, B; Zoom), a
 * finger's drag (D). Menu: Save Image, Zoom. Save Image puts a copy in the phone's pictures,
 * Pictures/Messages (D: OS 7 asks for the folder, Save); nothing else is written. Not exported,
 * never over the lock screen; Save checks the keyguard again. The picture (an MMS part, READ_SMS)
 * is decoded off the main thread.
 */
class ViewerActivity : Activity() {
    companion object {
        private const val EXTRA_URI = "uri"

        fun intent(context: Context, uri: String): Intent = Intent(context, ViewerActivity::class.java).putExtra(EXTRA_URI, uri)
    }

    private val graph get() = (application as MessagesApp).graph
    private lateinit var scope: CoroutineScope
    private lateinit var picture: PictureView
    private lateinit var menuView: MenuView<ViewerAct>
    private var menu: Menu<ViewerAct>? = null
    private val glyphs = arrayOfNulls<Bitmap>(ViewerAct.entries.size)
    private var clicking = false
    private var held = false
    private var travel = Travel()
    private lateinit var uri: String
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.getStringExtra(EXTRA_URI) ?: return finish()
        picture = PictureView(this)
        menuView = MenuView(
            this,
            glyph = { glyphs[it.ordinal] },
            label = { getString(if (it == ViewerAct.SAVE_IMAGE) R.string.viewer_save_image else R.string.viewer_zoom) },
            onEntry = ::chosen,
            onExpand = { menu?.let { show(it.expand()) } },
            onClose = { show(null) },
        )
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            setOnCapturedPointerListener { _, e -> onPointer(e) }
            addView(picture)
            addView(menuView)
        }
        setContentView(root)
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        scope = CoroutineScope(SupervisorJob() + graph.main)
        val side = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        scope.launch {
            val (bitmap, sm) = withContext(graph.io) {
                // At least twice the screen, for the zoom: a power of two smaller than the part.
                graph.threads.image(uri, side * 2) to
                    listOf(R.drawable.msg_sm_save_image_browser, R.drawable.msg_sm_zoom_in).map { BitmapFactory.decodeResource(resources, it) }
            }
            sm.forEachIndexed { i, b -> glyphs[i] = b }
            if (bitmap == null) {
                status(this@ViewerActivity, getString(R.string.viewer_unreadable))
                picture.postDelayed({ finish() }, dev.bold7.design.Durations.STATUS_MS)
            } else {
                picture.show(bitmap)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        root.requestFocus()
        root.requestPointerCapture()
    }

    override fun onDestroy() {
        if (::scope.isInitialized) scope.cancel()
        super.onDestroy()
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun show(m: Menu<ViewerAct>?) {
        menu = m
        menuView.bind(m)
    }

    private val entries = listOf(MenuEntry(ViewerAct.SAVE_IMAGE), MenuEntry(ViewerAct.ZOOM))

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (act) {
            ViewerAct.SAVE_IMAGE -> save()
            ViewerAct.ZOOM -> picture.zoomIn()
        }
    }

    /** Save Image: the part copied into the phone's pictures, said done or not. */
    private fun save() {
        if (locked()) return
        scope.launch {
            val ok = withContext(graph.io) { saveImage(this@ViewerActivity, Uri.parse(uri)) }
            status(this@ViewerActivity, getString(if (ok) R.string.viewer_saved else R.string.viewer_not_saved))
        }
    }

    private fun onMenuKey(m: Menu<ViewerAct>, key: MenuKey) {
        when (val r = m.key(key)) {
            is MenuResult.Show -> show(r.menu)
            is MenuResult.Choose -> chosen(r.index)
            MenuResult.Close -> show(null)
        }
    }

    private fun move(dir: Dir) {
        menu?.let {
            return onMenuKey(it, when (dir) {
                Dir.UP -> MenuKey.UP
                Dir.DOWN -> MenuKey.DOWN
                Dir.LEFT -> MenuKey.LEFT
                Dir.RIGHT -> MenuKey.RIGHT
            })
        }
        when (dir) {
            Dir.UP -> picture.pan(0, -1)
            Dir.DOWN -> picture.pan(0, 1)
            Dir.LEFT -> picture.pan(-1, 0)
            Dir.RIGHT -> picture.pan(1, 0)
        }
    }

    private fun back() {
        if (!picture.zoomOut()) finish()
    }

    private val hold = Runnable { if (clicking && menu == null) { show(Menu(entries, line = 0, highlighted = 1)); held = true } }

    /** The trackpad as a relative pointer: steps pan, the click zooms, held the short menu (D, as the thread). */
    private fun onPointer(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> if (!clicking) {
                clicking = true
                picture.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> if (clicking) {
                clicking = false
                picture.removeCallbacks(hold)
                val m = menu
                when {
                    held -> held = false
                    m != null -> onMenuKey(m, MenuKey.CLICK)
                    else -> picture.zoomIn()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                clicking = false
                picture.removeCallbacks(hold)
            }
            MotionEvent.ACTION_MOVE -> {
                var dx = e.x
                var dy = e.y
                for (i in 0 until e.historySize) {
                    dx += e.getHistoricalX(i)
                    dy += e.getHistoricalY(i)
                }
                val (next, steps) = travel.moved(dx, dy)
                travel = next
                steps.forEach(::move)
            }
        }
        return true
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) {
            if (click) {
                when {
                    held -> held = false
                    menu == null -> picture.zoomIn()
                }
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            onMenuKey(m, when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> if (click) MenuKey.CLICK else return true
            })
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> move(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> move(Dir.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(Dir.RIGHT)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> back()
            KeyEvent.KEYCODE_MENU -> show(Menu(entries, highlighted = 0))
            else -> when {
                click -> if (e.isLongPress || e.repeatCount == 1) { show(Menu(entries, line = 0, highlighted = 1)); held = true }
                // The Pictures shortcuts (guide 7.1 p. 38, B), by the character the key gives.
                else -> when (e.unicodeChar.toChar().lowercaseChar()) {
                    'i' -> picture.zoomIn()
                    'o' -> back()
                    '2' -> picture.pan(0, -1)
                    '8' -> picture.pan(0, 1)
                    '4' -> picture.pan(-1, 0)
                    '6' -> picture.pan(1, 0)
                    else -> return super.dispatchKeyEvent(e)
                }
            }
        }
        return true
    }
}

/** The picture drawn at its [Zoom] (Viewer.kt), on black; a finger's drag pans it (D). */
private class PictureView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var zoom: Zoom? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastX = 0f
    private var lastY = 0f

    private fun frame(): Frame? = bitmap?.takeIf { width > 0 }?.let { Frame(it.width, it.height, width, height) }

    fun show(b: Bitmap) {
        bitmap = b
        zoom = frame()?.let(Zoom::fit)
        invalidate()
    }

    fun zoomIn() = update { z, f -> z.zoomIn(f) }

    /** False when it was fitted: the viewer closes. */
    fun zoomOut(): Boolean {
        val f = frame() ?: return false
        val z = zoom?.zoomOut(f) ?: return false
        zoom = z
        invalidate()
        return true
    }

    fun pan(dx: Int, dy: Int) = update { z, f -> z.pan(dx, dy, f) }

    private inline fun update(change: (Zoom, Frame) -> Zoom) {
        val f = frame() ?: return
        zoom = change(zoom ?: Zoom.fit(f), f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        zoom = frame()?.let { f -> zoom?.pan(0, 0, f) ?: Zoom.fit(f) }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                val f = frame() ?: return true
                val z = zoom ?: return true
                zoom = z.drag(lastX - e.x, lastY - e.y, f)
                lastX = e.x
                lastY = e.y
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val f = frame() ?: return
        val z = zoom ?: return
        val s = z.scale(f)
        matrix.setTranslate(-z.x, -z.y)
        matrix.postScale(s, s)
        matrix.postTranslate(width / 2f, height / 2f)
        canvas.drawBitmap(b, matrix, paint)
    }
}

/**
 * Save Image: the MMS part at [part] copied into the phone's pictures (MediaStore, Pictures/Messages,
 * D), pending until whole; false when it could not be. Blocking: off the main thread only.
 */
fun saveImage(context: Context, part: Uri): Boolean {
    val resolver = context.contentResolver
    var item: Uri? = null
    return try {
        val type = resolver.getType(part)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, type)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Messages")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        item = target
        val input = resolver.openInputStream(part) ?: throw IOException("no stream")
        input.use { i -> (resolver.openOutputStream(target) ?: throw IOException("no output")).use { i.copyTo(it) } }
        resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (e: IOException) {
        unsaved(context, item, e)
    } catch (e: RuntimeException) {
        unsaved(context, item, e)
    }
}

private fun unsaved(context: Context, item: Uri?, e: Exception): Boolean {
    Log.w(TAG, "image not saved: ${e.javaClass.name}")
    item?.let { runCatching { context.contentResolver.delete(it, null, null) } }
    return false
}
