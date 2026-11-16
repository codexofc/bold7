package dev.bold7.launcher.switcher

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import dev.bold7.design.Focus
import dev.bold7.design.Typography
import dev.bold7.design.Veil
import dev.bold7.launcher.R
import dev.bold7.launcher.graph
import dev.bold7.launcher.home.CalendarDay
import dev.bold7.launcher.quickstep.Transitions
import dev.bold7.launcher.quickstep.WindowZoom
import dev.bold7.ui.play
import dev.bold7.platform.RemoteLaunches
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Dir
import dev.bold7.ui.Travel
import dev.bold7.ui.fit
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val TAG = "Bold7/Switcher"
private const val FROM_HOME = "from_home"

/** Names the home's entry (sim-05, the 9900's "Home Screen"). */
private const val HOME_LABEL = "Home Screen"

/**
 * Shows the switcher over what is on screen: the home's long Menu (HomeKeys) or SystemUI's
 * onOverviewToggle (QuickstepService) start it, and so do the apps' Switch Application rows
 * (SwitchApplication.ACTION, behind a signature permission). Nothing is shown while the phone is locked, and a
 * start that fails is logged, never thrown (the process holds quickstep).
 */
fun startSwitcher(context: Context, fromHome: Boolean) {
    if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
    try {
        context.startActivity(
            Intent(context, SwitcherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(FROM_HOME, fromHome),
            RemoteLaunches.plain(),
        )
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "switcher not started", e)
    } catch (e: SecurityException) {
        Log.w(TAG, "switcher refused", e)
    }
}

/**
 * The app switcher of OS 7: a translucent activity over the app or the home,
 * which is why it works from any app without an accessibility service. The icons of the open
 * apps come from FramesModel (the system's recents, io only); a click opens one through
 * SystemRecentTasks, Escape or Menu closes, and it closes as soon as it stops (home key, a call).
 * Started again while it shows, it closes: a second toggle.
 */
class SwitcherActivity : Activity() {
    private lateinit var scope: CoroutineScope
    private lateinit var view: SwitcherView
    private var entries: List<Entry> = listOf(Entry.Home)
    private var fromHome = false
    private var fading = false
    private var pressed = false // Enter or the trackpad is down
    private var travel = Travel()
    private val icons = HashMap<String, Bitmap?>() // main thread only
    private val days = HashSet<String>() // the icon keys of the calendar, which wears the day (main thread only)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Also started by the apps' Switch Application (a screen over the lock, the call's, asks too).
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            finish()
            return
        }
        val graph = graph
        scope = CoroutineScope(SupervisorJob() + graph.main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "switcher work failed", e) })
        fromHome = intent.getBooleanExtra(FROM_HOME, false)
        view = SwitcherView(this, fromHome, ::open, ::closeFading)
        view.onPointer = ::onPointer
        setContentView(view)
        window.hideStatusBar() // at once: OS 7 has no bar to slide away
        view.show(entries, emptyMap(), emptySet())
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.frames.state.collect { state ->
                val table = graph.apps.appTable()
                entries = entriesOf(state.frames, packageName) { table.hidden(it, "") }
                val missing = entries.filterIsInstance<Entry.Task>().distinctBy { it.iconKey }.filter { it.iconKey !in icons }
                // An app's icon is drawn once by the home's source; asking for it is a binder call.
                if (missing.isNotEmpty()) withContext(graph.ioDispatcher) {
                    missing.map { t -> t to graph.apps.activitiesOf(t.packageName).firstOrNull { t.activity == null || it.className == t.activity } }
                }.forEach { (t, app) ->
                    icons[t.iconKey] = app?.let(graph.apps::icon)
                    if (app != null && graph.apps.showsDay(app)) days += t.iconKey
                }
                view.show(entries, icons, days)
            }
        }
    }

    // The recents are followed only while the switcher shows.
    override fun onStart() {
        super.onStart()
        graph.frames.start()
    }

    override fun onStop() {
        graph.frames.stop()
        finish()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeFading()
    }

    /** Escape, Menu, a choice: the switcher fades out over what is below (§ 36); it appeared as a cut. */
    private fun closeFading() {
        if (fading) return
        fading = true
        view.play(Transitions.SWITCHER_CLOSE) { finish() }
    }

    @Suppress("DEPRECATION") // deprecated in API 34, kept: it is the call that drops the close animation
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0) // application-switcher-animation-on = false (theme.xml, A)
    }

    override fun onDestroy() {
        if (::scope.isInitialized) scope.cancel() // not made when the phone is locked
        super.onDestroy()
    }

    private fun open(index: Int) {
        if (fading) return
        when (val entry = entries.getOrNull(index)) {
            null -> Unit
            // Over the home, closing is enough. Over an app, the return to the home is the
            // launcher's own transition (WindowZoom.close, registered by QuickstepService).
            // The switcher leaves at once there: the zoom of the return takes the screen (D).
            Entry.Home -> if (!fromHome) {
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), RemoteLaunches.plain())
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no home to start", e)
                }
                return finish()
            }
            // Shown at once, no animation (WindowZoom.cut). Closed once the system has answered:
            // closing first would show the screen before for an instant.
            is Entry.Task -> return graph.frames.open(entry.taskId, WindowZoom.cut) { runOnUiThread { closeFading() } }
        }
        closeFading()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) view.step(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) view.step(Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) view.step(Dir.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) view.step(Dir.RIGHT)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                down -> if (e.repeatCount == 0) pressed = true
                else -> click(!e.isCanceled)
            }
            // Escape closes (guide 7.1, M). Menu closes too (D): only its first down, so the
            // up of the press that opened the switcher, and its repeats, change nothing.
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> if (!down && !e.isCanceled) closeFading()
            KeyEvent.KEYCODE_MENU -> if (down && e.repeatCount == 0) closeFading()
            else -> return super.dispatchKeyEvent(e) // volume, and the rest as Android has it
        }
        return true
    }

    private fun click(valid: Boolean) {
        val was = pressed
        pressed = false
        if (was && valid) view.focused?.let(::open)
    }

    /** The trackpad as a relative pointer (the Q25 sends it in some apps, arrows in others). */
    private fun onPointer(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> pressed = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> click(true)
            MotionEvent.ACTION_CANCEL -> pressed = false
            MotionEvent.ACTION_MOVE -> {
                var dx = e.x
                var dy = e.y
                for (i in 0 until e.historySize) {
                    dx += e.getHistoricalX(i)
                    dy += e.getHistoricalY(i)
                }
                val (next, steps) = travel.moved(dx, dy)
                travel = next
                steps.forEach { pressed = false; view.step(it) }
            }
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            view.requestFocus()
            view.requestPointerCapture()
        }
    }

}

/**
 * The veil, the focused entry's name, the icons in their cells and the focus behind one (sim-05):
 * drawn by this one view, no child.
 */
internal class SwitcherView(
    context: Context,
    private val fromHome: Boolean,
    private val open: (Int) -> Unit,
    private val close: () -> Unit,
) : View(context) {
    private var entries: List<Entry> = emptyList()
    private var icons: Map<String, Bitmap?> = emptyMap()
    private var days: Set<String> = emptySet()
    private val today = CalendarDay()
    private var cells: List<Cell> = emptyList()
    private var moved = false

    /** The entry with the focus, or null before the first state. */
    var focused: Int? = null
        private set

    private val home: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.home_net_rim_bb_ribbon_app)
    private val veil = Paint().apply { color = Veil.Switcher }
    private val focusFill = focusPaint(FOCUS_HEIGHT, Focus.IconTop, Focus.IconBottom)
    private val label = textPaint(Typography.TEXT, Typography.REGULAR, 0xFFFFFFFF.toInt(), align = Paint.Align.CENTER)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var nameKey = ""
    private var nameWidth = -1
    private var nameShown = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
    }

    fun show(entries: List<Entry>, icons: Map<String, Bitmap?>, days: Set<String>) {
        this.entries = entries
        this.icons = icons
        this.days = days
        today.show(LocalDate.now().dayOfMonth)
        // Until the trackpad moves, the focus follows the list as the system's answer comes in.
        focused = if (moved) (focused ?: 0).coerceAtMost(entries.size - 1) else initialFocus(entries.size, fromHome)
        cells = cells(entries.size, width, height)
        invalidate()
    }

    fun step(dir: Dir) {
        moved = true
        focused = moveFocus(focused ?: 0, dir, entries.size)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cells = cells(entries.size, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
        val f = focused ?: return
        val top = cells.firstOrNull()?.y ?: return
        val name = when (val e = entries.getOrNull(f)) {
            Entry.Home -> HOME_LABEL
            is Entry.Task -> e.label
            null -> ""
        }
        // sim-05: the name's baseline 16 px over the first row, centred on the screen.
        // Cut to the screen less 24 px each side, once per name and width (never in every frame).
        if (name != nameKey || width != nameWidth) {
            nameKey = name
            nameWidth = width
            nameShown = label.fit(name, width - 48f)
        }
        canvas.drawText(nameShown, width / 2f, top - 16f, label)
        cells.getOrNull(f)?.let {
            canvas.save()
            canvas.translate(it.x.toFloat(), it.y.toFloat())
            canvas.drawRect(0f, 0f, CELL_WIDTH.toFloat(), FOCUS_HEIGHT.toFloat(), focusFill)
            canvas.restore()
        }
        entries.forEachIndexed { i, entry ->
            val icon = if (entry is Entry.Task) icons[entry.iconKey] else home
            val cell = cells.getOrNull(i) ?: return@forEachIndexed
            icon?.let { canvas.drawBitmap(it, cell.x + (CELL_WIDTH - it.width) / 2f, cell.y.toFloat(), bitmapPaint) }
            // The calendar wears the day, as on the grid (sim-06, C).
            if (icon != null && entry is Entry.Task && entry.iconKey in days) {
                canvas.save()
                canvas.translate(0f, cell.y.toFloat())
                today.draw(canvas, cell.x + CELL_WIDTH / 2f)
                canvas.restore()
            }
        }
    }

    // A tap opens the entry under the finger; one outside every cell closes (D).
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) hit(cells, e.x, e.y).let { if (it >= 0) open(it) else close() }
        return true
    }

    var onPointer: (MotionEvent) -> Boolean = { false }

    override fun onCapturedPointerEvent(event: MotionEvent) = onPointer(event)
}
