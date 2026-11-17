package dev.bold7.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager

/** A view drawing a focus among a few items: what [FocusDialog] moves. */
interface Focused {
    var focused: Int
}

/**
 * A dialog window over an app or the home, its own focus among [count] items of [view]: the
 * trackpad moves it, as arrows or as the relative pointer it also is (docs/DEVICE.md; captured
 * while the window has the focus, as the home's HomeKeys), its click or Enter chooses, Back or
 * Escape cancels; a touch on an item chooses it ([itemAt]), elsewhere [touchOutsideCancels]. The
 * chosen item reaches [onChosen] after the window closes, or with it still open when [closes]
 * says so (Manage Connections' check boxes). No animation, no veil (sim-19, C; D for
 * the animation). Shared by [Bold7Dialog] and the home's profile popup.
 *
 * [hideStatusBar]: a window over the home, which hides Android's bar for its own banner, hides it
 * in this one too; so does a window over an app that has hidden the bar for its own title bar or
 * none (the dialog's window would otherwise set the bar's look again and show Android's black);
 * over an app that keeps the bar it stays, as OS 7's popups leave the banner.
 */
abstract class FocusDialog<V>(
    context: Context,
    protected val view: V,
    private val count: Int,
    private val hideStatusBar: Boolean = false,
    private val touchOutsideCancels: Boolean = false,
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) where V : View, V : Focused {
    private var travel = Travel()
    private var clicking = false

    /** The item at ([x], [y]) of [view], or null. */
    protected abstract fun itemAt(x: Float, y: Float): Int?

    protected abstract fun onChosen(item: Int)

    /** Whether choosing [item] closes the window (all do, unless a dialog says otherwise). */
    protected open fun closes(item: Int): Boolean = true

    /** The item a step [dir] leads to from [from]: the next or the one before, by default (a list of buttons). */
    protected open fun next(from: Int, dir: Dir): Int =
        (from + if (dir == Dir.UP || dir == Dir.LEFT) -1 else 1).coerceIn(0, count - 1)

    /** A key the focus does not take (a button's letter); true if taken. */
    protected open fun onOtherKey(e: KeyEvent): Boolean = false

    init {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.defaultFocusHighlightEnabled = false
        setContentView(view)
        view.setOnCapturedPointerListener { _, e -> onPointer(e) }
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            fitBars() // under the banner, as an activity (Bars.kt)
        }
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val item = itemAt(e.x, e.y)
                if (item != null) choose(item) else if (touchOutsideCancels) cancel()
            }
            true
        }
    }

    /** Whether the bar is hidden here: asked, or hidden already in the activity this dialog lies over. */
    private fun barHidden(): Boolean {
        if (hideStatusBar) return true
        val owner = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        return owner?.window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars()) == false
    }

    private fun hideBar() {
        if (barHidden()) window?.decorView?.windowInsetsController?.hide(WindowInsets.Type.statusBars())
    }

    override fun onStart() {
        super.onStart()
        hideBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) return
        hideBar()
        view.requestFocus()
        view.requestPointerCapture()
    }

    /**
     * The trackpad as a relative pointer: its steps move the focus, its button (BTN_MOUSE) chooses
     * on release. Captured events go to the focused view: a dialog that focuses a child (a text
     * field) hands them here from that child.
     */
    protected fun onPointer(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> clicking = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> if (clicking) {
                clicking = false
                choose(view.focused)
            }
            MotionEvent.ACTION_CANCEL -> clicking = false
            MotionEvent.ACTION_MOVE -> {
                // Each sample is a move from the one before.
                var dx = e.x
                var dy = e.y
                for (i in 0 until e.historySize) {
                    dx += e.getHistoricalX(i)
                    dy += e.getHistoricalY(i)
                }
                val (next, steps) = travel.moved(dx, dy)
                travel = next
                for (d in steps) view.focused = next(view.focused, d)
            }
        }
        return true
    }

    protected fun choose(item: Int) {
        if (closes(item)) dismiss()
        onChosen(item)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> view.focused = next(view.focused, Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> view.focused = next(view.focused, Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> view.focused = next(view.focused, Dir.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> view.focused = next(view.focused, Dir.RIGHT)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> choose(view.focused)
            KeyEvent.KEYCODE_ESCAPE -> cancel()
            else -> if (!onOtherKey(e)) return super.dispatchKeyEvent(e)
        }
        return true
    }
}
