package dev.bold7.platform

import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Lays [this] window over the whole screen and hides Android's status bar there, a swipe from the
 * top edge showing it for a moment (transient bars). OS 7 has no such bar, so it goes at once:
 * the window's insets animations are turned off (the hidden setAnimationsDisabled, kept by the
 * pending controller until the window is attached), the bar does not slide away.
 * Every later show or hide of that window's insets is instant too, the keyboard's included.
 */
fun Window.hideStatusBar() {
    setDecorFitsSystemWindows(false)
    attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    // Through decorView, which installs the decor if setContentView has not run yet:
    // Window.insetsController alone throws a NullPointerException before it.
    decorView.windowInsetsController?.apply {
        disableAnimations?.invoke(this, true)
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsets.Type.statusBars())
    }
}

/**
 * The hidden setAnimationsDisabled, found once: only the launcher, a priv-app whose hidden API
 * access the module allows, sees it. An ordinary app (Messages) gets NoSuchMethodException and
 * keeps Android's slide of the bar: calling it directly made Messages crash on every thread
 * opened (NoSuchMethodError, seen on the Q25).
 */
private val disableAnimations by lazy {
    try {
        WindowInsetsController::class.java.getMethod("setAnimationsDisabled", Boolean::class.javaPrimitiveType)
    } catch (e: NoSuchMethodException) {
        null
    }
}
