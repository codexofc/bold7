package dev.bold7.ui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
import android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST

/**
 * From target 35 Android lays every window edge to edge, under its bars, and ignores
 * setDecorFitsSystemWindows(true): a screen that kept the bar (Messages, Clock's Options) began
 * under OS 7's banner. [fitBars] puts back what the decor did up to target 34, in one place.
 *
 * A window that lays itself out under the bars says so by LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
 * which it sets anyway: core/platform's hideStatusBar, [setContentUnderBar]. It is left alone.
 */
enum class BarFit { NONE, BARS, BARS_AND_IME }

/** What a window gets: nothing if it lays itself out, else the bars, and the keyboard if it resizes for it. */
fun barFit(cutoutMode: Int, softInputMode: Int): BarFit = when {
    cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS -> BarFit.NONE
    softInputMode and SOFT_INPUT_MASK_ADJUST == SOFT_INPUT_ADJUST_RESIZE -> BarFit.BARS_AND_IME
    else -> BarFit.BARS
}

/**
 * Pads this window's content by its visible bars (0 where the bar is hidden), and the keyboard
 * for an adjustResize window, as the decor did up to target 34. Read when the insets come, so a
 * window that hides its bar or lays itself out after this call is still right.
 */
fun Window.fitBars() {
    val window = this
    decorView.findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { v, insets ->
        val bars = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val types = when (barFit(window.attributes.layoutInDisplayCutoutMode, window.attributes.softInputMode)) {
            BarFit.NONE -> 0
            BarFit.BARS -> bars
            BarFit.BARS_AND_IME -> bars or WindowInsets.Type.ime()
        }
        val i = insets.getInsets(types)
        v.setPadding(i.left, i.top, i.right, i.bottom)
        insets
    }
}

/**
 * Fits every activity of the app ([fitBars]) without a line in it: Android creates it before
 * Application.onCreate in each app that links core/ui (its manifest merges it, not exported). It serves nothing.
 */
class BarsProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // After onCreate: its setContentView has made the decor (made earlier, it would fix the theme and window features).
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.window.peekDecorView() != null) activity.window.fitBars()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
