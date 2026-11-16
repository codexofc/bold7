package dev.bold7.sysui

import android.content.Context
import android.graphics.Canvas
import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.KeyguardPINView
import com.android.keyguard.KeyguardPasswordView
import com.android.keyguard.KeyguardStatusView
import com.android.keyguard.PasswordTextView

// What Bouncer reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar), like
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

// MediaTek's SIM view: its inflation, its layout, and the state its controller is in,
// read to choose OS 7's label; the digits stay in its PasswordTextView, of which the box reads the
// count only (entry.mTextChars, above).
@Suppress("unused", "UNUSED_VARIABLE")
private fun simTargets(sim: com.mediatek.keyguard.Telephony.KeyguardSimPinPukMeView) {
    sim.onFinishInflate()
    val layout: android.widget.LinearLayout = sim
    val state: Int = sim.mKeyguardSimPinPukMeViewController.mUnlockEnterState
}

// What the box reads of the bouncer around it, and what the scrims' hook reads and writes
// (LockFace.clearScrims).
@Suppress("unused", "UNUSED_VARIABLE")
private fun boxTargets(container: com.android.keyguard.KeyguardSecurityContainer, scrims: com.android.systemui.statusbar.phone.ScrimController) {
    val disappearing: Boolean = container.mDisappearAnimRunning
    scrims.applyState()
    val state: com.android.systemui.statusbar.phone.ScrimState = scrims.mState
    val name: String = state.name
    scrims.mBehindAlpha = 0f
    scrims.mNotificationsAlpha = 0f
    val qs: Float = scrims.mQsExpansion
    val fullShade: Float = scrims.mTransitionToFullShadeProgress
}

// What LockFace reaches by name.
@Suppress("unused", "UNUSED_VARIABLE")
private fun lockFaceTargets(
    stack: com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout,
    area: com.android.systemui.statusbar.phone.KeyguardBottomAreaView,
    indication: com.android.systemui.statusbar.phone.KeyguardIndicationTextView,
    icon: com.android.keyguard.LockIconView,
    status: KeyguardStatusView,
    canvas: Canvas,
    context: Context,
) {
    val state: Int = stack.mStatusBarState
    stack.dispatchDraw(canvas)
    stack.onDraw(canvas)
    status.dispatchDraw(canvas)
    area.onFinishInflate()
    indication.setNextIndication()
    val images: List<android.widget.ImageView> = listOf(icon.mLockIcon, icon.mBgView)
    icon.onFinishInflate()
    val under: com.android.systemui.statusbar.notification.row.ExpandableView? = stack.getChildAtRawPosition(0f, 0f)
    val dozing: Boolean = stack.mAmbientState.mDozing
    val dozeAmount: Float = stack.mAmbientState.mDozeAmount
    stack.onInterceptTouchEvent(android.view.MotionEvent.obtain(0, 0, 0, 0f, 0f, 0))
    // onTouchEvent is declared in the dex (dexdump), the stubs dropped it: compiled against View.
    stack.onTouchEvent(android.view.MotionEvent.obtain(0, 0, 0, 0f, 0f, 0))
    // The owner's information, as KeyguardIndicationController reads it (framework stubs).
    val utils = LockPatternUtils(context)
    val owner: String? = utils.getDeviceOwnerInfo() ?: if (utils.isOwnerInfoEnabled(0)) utils.getOwnerInfo(0) else null
}

// What the Call key's hook reads on the lock screen (PlaceCall.kt): the shade
// window's key dispatch, then from its controller the bar's state, whether the device is awake, and
// the keyguard's showing, occluded and bouncer.
@Suppress("unused", "UNUSED_VARIABLE")
private fun placeCallTargets(window: com.android.systemui.shade.NotificationShadeWindowView, key: android.view.KeyEvent) {
    val handled: Boolean = window.dispatchKeyEvent(key)
    val controller: com.android.systemui.shade.NotificationShadeWindowViewController = window.mInteractionEventHandler.`this$0`
    val surfaces = controller.mService as com.android.systemui.statusbar.phone.CentralSurfacesImpl
    val state: Int = surfaces.mState
    val interactive: Boolean = surfaces.mDeviceInteractive
    val keyguard = surfaces.mKeyguardStateController as com.android.systemui.statusbar.policy.KeyguardStateControllerImpl
    val showing: Boolean = keyguard.mShowing
    val occluded: Boolean = keyguard.mOccluded
    val bouncer: Boolean = keyguard.mPrimaryBouncerShowing
}
