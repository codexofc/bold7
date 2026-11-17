package dev.bold7.sysui

import android.animation.ValueAnimator
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.NotificationPanelViewController
import com.android.systemui.shade.QuickSettingsControllerImpl
import com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks
import com.android.systemui.statusbar.policy.KeyguardStateControllerImpl
import android.view.WindowManagerGlobal
import android.content.Context
import android.view.MotionEvent
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.phone.`PhoneStatusBarViewController$PhoneStatusBarViewTouchHandler`

// What ShadeHooks reaches by name, compiled against LineageOS 24's SystemUI (stubs/systemui.jar) and
// framework (stubs/framework.jar), like HookTargets.kt. Never run, the stubs have no code. The
// methods R8 suffixes (NotificationStackScrollLayout.updateVisibility$*,
// LockscreenShadeTransitionController.isDragDownEnabledForView$*) are the table's, never named here.
@Suppress("unused", "UNUSED_VARIABLE")
private fun shadeTargets(panel: NotificationPanelViewController, stack: NotificationStackScrollLayout, bar: IStatusBarService) {
    panel.updateExpansionAndVisibility()
    val fraction: Float = panel.mExpandedFraction
    val tracking: Boolean = panel.isTracking()
    val instant: Boolean = panel.mInstantExpanding
    val animator: ValueAnimator? = panel.mHeightAnimator
    val cardPinned: Boolean = (panel.mHeadsUpManager as HeadsUpManagerImpl).mHasPinnedNotification
    val view: NotificationPanelView = panel.mView
    val qs: QuickSettingsControllerImpl = panel.mQsController
    val panelStack: NotificationStackScrollLayout = panel.mNotificationStackScrollLayoutController.mView
    val panelState: Int = panel.mBarState
    panel.animateCollapseQs(false)
    panel.collapse(1f, true, false)
    qs.setExpandImmediate(true)
    val intercepts: Boolean = qs.shouldQuickSettingsIntercept(0f, 0f, 0f, null)
    val qsState: Int = qs.mBarState
    val expanded: Boolean = qs.getExpanded()
    val locked: Boolean = (qs.mKeyguardStateController as KeyguardStateControllerImpl).mShowing
    val stackState: Int = stack.mStatusBarState
    // Implemented by IStatusBarService$Stub$Proxy, SystemUI's mBarService.
    bar.clearNotificationEffects()
    bar.onPanelRevealed(true, 1)
}

// The home's bar (homeHidesBar): the hook after SystemUI's transient bars, and what undoes them.
@Suppress("unused")
private fun homeBarTargets(callbacks: CentralSurfacesCommandQueueCallbacks) {
    callbacks.showTransient(0, 1, false)
    WindowManagerGlobal.getWindowManagerService().hideTransientBars(0)
}

// The shade shut: the status bar's touches, the lock screen's drag down, the
// requests to expand, and where each reads the switch from.
@Suppress("unused", "UNUSED_VARIABLE")
private fun shutTargets(
    touch: `PhoneStatusBarViewController$PhoneStatusBarViewTouchHandler`,
    transition: LockscreenShadeTransitionController,
    callbacks: CentralSurfacesCommandQueueCallbacks,
    qs: QuickSettingsControllerImpl,
    event: MotionEvent,
) {
    val eaten: Boolean = touch.onTouchEvent(event)
    val barContext: Context = touch.`this$0`.mView.context
    val transitionContext: Context = transition.context
    callbacks.animateExpandNotificationsPanel()
    callbacks.animateExpandSettingsPanel(null)
    val callbacksContext: Context = callbacks.mContext
    val qsContext: Context = qs.mPanelView.context
}
