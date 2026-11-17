package dev.bold7.sysui

import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.DialogView
import dev.bold7.ui.mnemonic
import java.lang.reflect.Method

/**
 * OS 7's dialog over Android's power menu (GlobalActionsDialogLite.ActionsDialogLite): the 9900
 * powered off at once when its key was held (manual p. 301, B), and asked Full Power Off or Turn
 * Off only with Auto On/Off set; Bold7 always asks, with Android's power off, restart and
 * emergency behind the buttons (D). The dialog is core/ui's own (DialogView, sim-19, sim-47),
 * its images read from this module's resources. Android keeps the dialog, its window, its
 * dismissal (back, screen off, system dialogs closed), the list of actions and every rule behind
 * it (keyguard, provisioning, user restrictions, trust revoked on the keyguard): the dialog only
 * shows some of Android's actions and runs them through Android's own adapter
 * (MyAdapter.onClickItem: dismiss, then the action's onPress). It never adds one.
 */
internal class PowerMenuScreen(
    loader: ClassLoader,
    private val images: (Context) -> Images?,
    private val fail: (String, Exception) -> Unit,
    /** Told while the dialog's window is up: the status bar stays as it was under it (barKept). */
    private val shown: (Boolean) -> Unit,
) {
    val onCreate: Method = loader.loadClass(ACTIONS_DIALOG).getDeclaredMethod("onCreate", android.os.Bundle::class.java)
    private val adapters = listOf("mAdapter", "mOverflowAdapter", "mPowerOptionsAdapter").map { loader.loadClass(ACTIONS_DIALOG).getField(it) }
    private val onClickItem = loader.loadClass(ACTIONS_ADAPTER).getMethod("onClickItem", Int::class.java)

    /** After Android's onCreate: our dialog over Android's menu, which stays underneath, hidden. */
    fun dress(dialog: Dialog) {
        val images = images(dialog.context) ?: return
        val offered = LinkedHashMap<PowerAction, Pair<BaseAdapter, Any>>()
        for (field in adapters) {
            val adapter = field.get(dialog) as BaseAdapter
            for (i in 0 until adapter.count) {
                val item = adapter.getItem(i)
                powerAction(item.javaClass.name)?.let { offered.putIfAbsent(it, adapter to item) }
            }
        }
        val buttons = powerButtons(offered.keys)
        val labels = buttons.map { images.text(label(it)) }
        val view = DialogView(images.context, images.text(R.string.power_question), labels, DialogIcon.QUESTION)
        view.focused = powerFocus(buttons)
        val content = dialog.findViewById<ViewGroup>(android.R.id.content)
        val stock = (0 until content.childCount).map(content::getChildAt)
        val restoreAndroid = {
            content.removeView(view)
            stock.forEach { it.visibility = View.VISIBLE }
        }

        fun choose(i: Int) {
            val action = buttons[i].action ?: return dialog.dismiss()
            runAction(offered, action, restoreAndroid)
        }

        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.defaultFocusHighlightEnabled = false
        // The trackpad's arrows (bold7-trackpad), its click or Enter, Escape, a button's letter.
        view.setOnKeyListener { _, _, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> view.focused = (view.focused - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> view.focused = (view.focused + 1).coerceAtMost(buttons.size - 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> choose(view.focused)
                KeyEvent.KEYCODE_ESCAPE -> dialog.dismiss()
                else -> choose(mnemonic(labels, e.displayLabel) ?: return@setOnKeyListener false)
            }
            true
        }
        // A touch on a button chooses it; elsewhere nothing (core/ui's Bold7Dialog, D).
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) view.layout?.buttonAt(e.x, e.y)?.let(::choose)
            true
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener { if (it) view.requestFocus() }
        // Before the window is added, so before its look reaches the bar; let go once it is removed.
        shown(true)
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = shown(true)
            override fun onViewDetachedFromWindow(v: View) = shown(false)
        })
        content.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stock.forEach { it.visibility = View.INVISIBLE }
        // OS 7 does not darken what is under a dialog (sim-19, C): no dim, which Android animates
        // from mWindowDimAmount only while the flag is there.
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        Log.i(TAG, "power menu: OS 7 dialog with ${offered.keys.joinToString()}")
    }

    /** Android's own path for the action, found again by identity: the lists may have changed. */
    private fun runAction(offered: Map<PowerAction, Pair<BaseAdapter, Any>>, action: PowerAction, restoreAndroid: () -> Unit) {
        try {
            val (adapter, item) = offered.getValue(action)
            val index = (0 until adapter.count).firstOrNull { adapter.getItem(it) === item }
            if (index == null) {
                // Android no longer offers it: back to Android's menu, as it stands.
                Log.w(TAG, "power menu: $action no longer offered")
                restoreAndroid()
                return
            }
            onClickItem.invoke(adapter, index)
        } catch (e: ReflectiveOperationException) {
            fail("power menu: $action failed, Android's menu back", e)
            restoreAndroid()
        } catch (e: RuntimeException) {
            fail("power menu: $action failed, Android's menu back", e)
            restoreAndroid()
        }
    }

    private fun label(b: PowerButton) = when (b) {
        PowerButton.FULL_POWER_OFF -> R.string.power_full_off
        PowerButton.RESTART -> R.string.power_restart
        PowerButton.CANCEL -> R.string.power_cancel
        PowerButton.EMERGENCY -> R.string.power_emergency
    }
}
