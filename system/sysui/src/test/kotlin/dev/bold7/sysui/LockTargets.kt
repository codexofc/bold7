package dev.bold7.sysui

import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.KeyguardPINView
import com.android.keyguard.KeyguardPasswordView
import com.android.keyguard.PasswordTextView

// What Bouncer reaches by name, compiled against LineageOS 24's SystemUI (stubs/systemui.jar), like
// HookTargets.kt. Never run, the stubs have no code.
@Suppress("unused", "UNUSED_VARIABLE")
private fun bouncerTargets(pin: KeyguardPINView, password: KeyguardPasswordView, entry: PasswordTextView, key: android.view.KeyEvent) {
    val handled: Boolean = pin.onKeyDown(android.view.KeyEvent.KEYCODE_1, key)
    pin.onFinishInflate()
    password.onFinishInflate()
    // Both are LinearLayouts (KeyguardInputView), the cover goes over their children (Bouncer.overlay).
    val pinLayout: android.widget.LinearLayout = pin
    val passwordLayout: android.widget.LinearLayout = password
    // The cover reads how many characters Android holds, never which.
    val chars: java.util.ArrayList<*> = entry.mTextChars
}

// AOSP's SIM views: their inflation, their layout (KeyguardInputView, a LinearLayout) and the
// key handling they share with the device's PIN; the digits stay in their PasswordTextView, of
// which the box reads the count only (entry.mTextChars, above).
@Suppress("unused", "UNUSED_VARIABLE")
private fun simTargets(pin: com.android.keyguard.KeyguardSimPinView, puk: com.android.keyguard.KeyguardSimPukView, key: android.view.KeyEvent) {
    pin.onFinishInflate()
    puk.onFinishInflate()
    val layouts: List<android.widget.LinearLayout> = listOf(pin, puk)
    val keys: List<com.android.keyguard.KeyguardPinBasedInputView> = listOf(pin, puk)
    keys[0].onKeyDown(android.view.KeyEvent.KEYCODE_1, key)
}

// What the box reads of the bouncer around it, and what the scrims' hook reads and writes
// (LockFace.clearScrims).
@Suppress("unused", "UNUSED_VARIABLE")
private fun boxTargets(container: com.android.keyguard.KeyguardSecurityContainer, scrims: com.android.systemui.statusbar.phone.ScrimController) {
    val disappearing: Boolean = container.mDisappearAnimRunning
    // applyState$* carries R8's suffix, which a build renumbers: resolved by the table, not named here.
    val state: com.android.systemui.statusbar.phone.ScrimState = scrims.mState
    val name: String = state.name
    scrims.mBehindAlpha = 0f
    scrims.mNotificationsAlpha = 0f
    val qs: Float = scrims.mQsExpansion
    val fullShade: Float = scrims.mTransitionToFullShadeProgress
}

// LockFace (group lockface) on LineageOS 24's keyguard: the shade window it joins, the root it
// leaves undrawn (KeyguardRootView, final, a ConstraintLayout drawing through ConstraintLayout's
// dispatchDraw), and the stack it hides, read as on BenOS.
@Suppress("unused", "UNUSED_VARIABLE")
private fun faceTargets(
    window: com.android.systemui.shade.NotificationShadeWindowView,
    root: com.android.systemui.keyguard.ui.view.KeyguardRootView,
    stack: com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout,
    canvas: android.graphics.Canvas,
) {
    window.onAttachedToWindow()
    val frame: android.widget.FrameLayout = window
    val layout: androidx.constraintlayout.widget.ConstraintLayout = root
    layout.dispatchDraw(canvas)
    val state: Int = stack.mStatusBarState
    val ambient: com.android.systemui.statusbar.notification.stack.AmbientState = stack.mAmbientState
    val dozing: Boolean = ambient.mDozing
    val dozeAmount: Float = ambient.mDozeAmount
}

// PlaceCall (group placecall) is off on LineageOS 24: NotificationShadeWindowView.dispatchKeyEvent
// is gone. Its targets are BenOS's rows of hook-targets.tsv until an L6 lot rewrites them.
