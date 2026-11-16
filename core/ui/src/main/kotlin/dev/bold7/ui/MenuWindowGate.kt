package dev.bold7.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu as AndroidMenu
import android.view.Window

/**
 * [MenuKeyGate] in front of an activity's own key handling: every app routes its keys in its
 * dispatchKeyEvent, so the gate wraps the window's callback, which calls it, once per window. The
 * [MenuView]s attached to the window say whether a menu is open.
 */
internal class MenuWindowGate private constructor(private val inner: Window.Callback) : Window.Callback by inner {
    private val gate = MenuKeyGate()
    private val views = mutableListOf<MenuView<*>>()

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if ((down || e.action == KeyEvent.ACTION_UP) && gate.swallows(e.keyCode, down, e.repeatCount, views.any { it.open })) return true
        return inner.dispatchKeyEvent(e)
    }

    // Java's default methods, forwarded by hand.
    override fun onPointerCaptureChanged(hasCapture: Boolean) = inner.onPointerCaptureChanged(hasCapture)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: AndroidMenu?, deviceId: Int) =
        inner.onProvideKeyboardShortcuts(data, menu, deviceId)

    companion object {
        fun attach(view: MenuView<*>) {
            val window = view.context.activity()?.window ?: return
            val gate = window.callback as? MenuWindowGate ?: MenuWindowGate(window.callback ?: return).also { window.callback = it }
            gate.views += view
        }

        fun detach(view: MenuView<*>) {
            (view.context.activity()?.window?.callback as? MenuWindowGate)?.views?.remove(view)
        }

        private tailrec fun Context.activity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.activity()
            else -> null
        }
    }
}
