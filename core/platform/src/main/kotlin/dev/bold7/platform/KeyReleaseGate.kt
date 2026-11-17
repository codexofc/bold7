package dev.bold7.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window

/**
 * The presses a window received: the release of a key it never saw pressed is stopped. Bold7's
 * dialogs choose on the press and close; the release then reached the page under them, which
 * chooses on the release, and the trackpad's click counted twice (Setup's wallpaper chosen again
 * after its confirmation, a menu's row). A key held when the window came repeats here, its
 * release is not a click. Pure.
 */
class KeyReleaseGate {
    private val pressed = HashSet<Int>()

    /** Whether the event of key [code] ([down]: a press, [repeat] its count) stops here. */
    fun swallows(code: Int, down: Boolean, repeat: Int): Boolean {
        if (down) {
            if (repeat == 0) pressed += code
            return false
        }
        return !pressed.remove(code)
    }
}

/**
 * [KeyReleaseGate] in front of every activity of this app: its window's callback, wrapped once
 * when the activity is created (the menus' MenuWindowGate wraps it again, outside).
 */
fun Application.stopOrphanKeyReleases() = registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val window = activity.window ?: return
        val inner = window.callback ?: return
        if (inner !is GatedCallback) window.callback = GatedCallback(inner)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
})

private class GatedCallback(private val inner: Window.Callback) : Window.Callback by inner {
    private val gate = KeyReleaseGate()

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if ((down || e.action == KeyEvent.ACTION_UP) && gate.swallows(e.keyCode, down, e.repeatCount)) return true
        return inner.dispatchKeyEvent(e)
    }

    // Java's default methods, forwarded by hand (as MenuWindowGate).
    override fun onPointerCaptureChanged(hasCapture: Boolean) = inner.onPointerCaptureChanged(hasCapture)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        inner.onProvideKeyboardShortcuts(data, menu, deviceId)
}
