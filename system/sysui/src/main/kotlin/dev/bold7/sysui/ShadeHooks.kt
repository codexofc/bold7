package dev.bold7.sysui

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Field
import java.lang.reflect.Method

// The SystemUI classes ShadeHooks reaches by name (the module's class loader cannot see them).
// ShadeTest finds each in stubs/systemui.jar, ShadeTargets.kt compiles the members.
// NOTIFICATION_STACK is LockFace's.
internal const val QS_CONTROLLER = "com.android.systemui.shade.QuickSettingsController"
internal const val KEYGUARD_STATE = "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
internal const val PANEL_VIEW_CONTROLLER = "com.android.systemui.shade.NotificationPanelViewController"
/**
 * SystemUI's mBarService, the binder proxy of the status bar service: a framework class
 * (stubs/framework.jar), hooked in SystemUI's process only, outside the SystemUI fingerprint: to
 * read again after an OTA.
 */
internal const val STATUS_BAR_SERVICE_PROXY = "com.android.internal.statusbar.IStatusBarService\$Stub\$Proxy"
/** The status bar's touch handler, which hands a pull to the shade (installShut). */
internal const val STATUS_BAR_TOUCH = "com.android.systemui.statusbar.phone.PhoneStatusBarViewController\$PhoneStatusBarViewTouchHandler"
/** The lock screen's drag down to its shade (DragDownHelper's callback, installShut). */
internal const val LOCKSCREEN_SHADE = "com.android.systemui.statusbar.LockscreenShadeTransitionController"
private const val DRAG_DOWN_ENABLED = "isDragDownEnabledForView\$vendor__mediatek__proprietary__packages__apps__SystemUI__android_common__MtkSystemUI_core"
private const val EXPANDABLE_VIEW = "com.android.systemui.statusbar.notification.row.ExpandableView"

/** The emergency switch, read where it is needed (Settings.Global caches it, no binder call per touch). */
private fun androidShade(context: Context): Boolean =
    androidShadeAsked(Settings.Global.getInt(context.contentResolver, ANDROID_SHADE_SETTING, 0))

/**
 * The shade shut (shadeShut): OS 7 opens nothing from the top of an app nor of
 * the lock screen. Four hooks, read on the Q25's MtkSystemUI (jadx of stubs/device/MtkSystemUI.apk),
 * each alone, each on the switch (bold7_android_shade=1 gives Android's shade back at the next
 * gesture), plus the lock screen's quick settings in ShadeHooks' QuickSettingsController hook:
 * - PhoneStatusBarViewController$PhoneStatusBarViewTouchHandler.onTouchEvent(MotionEvent)Z, in
 *   place of Android's: a pull from the status bar of an app, or of an app shown over the lock
 *   screen, is eaten, never handed to the shade (NotificationPanelViewController's touch handler);
 *   decided at ACTION_DOWN for the whole gesture;
 * - LockscreenShadeTransitionController.isDragDownEnabledForView(ExpandableView)Z, after: no drag
 *   down from the lock screen to its shade (NotificationShadeWindowView, DragDownHelper);
 * - CentralSurfacesCommandQueueCallbacks.animateExpandNotificationsPanel()V and
 *   animateExpandSettingsPanel(String)V, instead: an app's request to expand it
 *   (StatusBarManager, normal permission EXPAND_STATUS_BAR), `cmd statusbar`, the keyboard's
 *   toggle (togglePanel calls the first) do nothing.
 * Untouched: the unlock gesture and the PIN entry (the panel's own touches), a pull on a heads-up
 * card HeadsUp.kt keeps (calls, alarms, full screen intents: its secours, Android's shade with it,
 * ShadeHooks). A missing target costs its path only, Android's again there: the quick settings
 * alone in an app (ShadeHooks), Android's locked shade, the request served.
 */
internal fun installShut(
    loader: ClassLoader,
    after: (String, () -> Method, (Chain, Any?) -> Any?) -> Boolean,
    instead: (String, () -> Method, (Chain) -> Boolean) -> Boolean,
    replace: (String, () -> Method, (Chain) -> Any?) -> Boolean,
) {
    var owner: Field? = null
    var ownerView: Field? = null
    // SystemUI's main thread only, like the touches.
    var barShut = false
    var logged = false
    replace("$STATUS_BAR_TOUCH.onTouchEvent(MotionEvent)Z + this\$0, mView", {
        val cls = loader.loadClass(STATUS_BAR_TOUCH)
        owner = cls.getField("this\$0").also { ownerView = it.type.getField("mView") }
        cls.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
    }) { chain ->
        if ((chain.getArg(0) as MotionEvent).actionMasked == MotionEvent.ACTION_DOWN) {
            // A failure below (counted) leaves this gesture to Android, not the last one's answer.
            barShut = false
            val view = ownerView!!.get(owner!!.get(chain.thisObject)) as View
            barShut = shadeShut(androidShade(view.context))
            if (barShut && !logged) {
                logged = true
                Log.i(TAG, "shade: shut, a pull from the status bar opens nothing ($ANDROID_SHADE_SETTING=1 gives it back)")
            }
        }
        statusBarTouchAnswer(barShut)
    }
    var transitionContext: Field? = null
    after("$LOCKSCREEN_SHADE.$DRAG_DOWN_ENABLED(ExpandableView)Z + context", {
        val cls = loader.loadClass(LOCKSCREEN_SHADE)
        transitionContext = cls.getField("context")
        cls.getDeclaredMethod(DRAG_DOWN_ENABLED, loader.loadClass(EXPANDABLE_VIEW))
    }) { chain, enabled ->
        dragDownEnabled(enabled as Boolean, androidShade(transitionContext!!.get(chain.thisObject) as Context))
    }
    var callbacksContext: Field? = null
    val callbacks = { loader.loadClass(SURFACES_CALLBACKS).also { callbacksContext = it.getField("mContext") } }
    val dropped = { chain: Chain -> shadeShut(androidShade(callbacksContext!!.get(chain.thisObject) as Context)) }
    instead("$SURFACES_CALLBACKS.animateExpandNotificationsPanel()V + mContext", { callbacks().getDeclaredMethod("animateExpandNotificationsPanel") }, dropped)
    instead("$SURFACES_CALLBACKS.animateExpandSettingsPanel(String)V + mContext", { callbacks().getDeclaredMethod("animateExpandSettingsPanel", String::class.java) }, dropped)
}

/**
 * The unlocked shade without Android's notifications (Shade.kt): Android's quick settings alone, the toggles, the
 * notifications in the home's list of BBOS 7. A resource cannot do it: no value hides the
 * notification stack, and Android's split shade (config_use_split_notification_shade), whose
 * quick settings behave this way, puts the notifications beside them. Six hooks, read on the
 * Q25's MtkSystemUI (jadx of stubs/device/MtkSystemUI.apk) and services.jar, each taking a path
 * this SystemUI already takes:
 * - NotificationStackScrollLayout.updateVisibility()V, after: the stack is INVISIBLE while the
 *   shade is alone, as Android makes it on the always-on display (neither drawn nor touched: its
 *   rows, footer with Manage and Clear all, section headers, shelf, empty shade text);
 * - QuickSettingsController.shouldQuickSettingsIntercept(FFF)Z, after: false while QS is expanded,
 *   as in the split shade: a swipe up closes the whole panel (NotificationPanelViewController's
 *   onTrackingStarted, QS fully expanded: expand immediate, shelf only), instead of folding QS
 *   into its quick row over the emptied list;
 * - NotificationPanelViewController.animateCollapseQs(Z)V, instead: Back closes the whole panel,
 *   collapse(1, true, false), the split shade's line;
 * - IStatusBarService$Stub$Proxy.clearNotificationEffects()V, instead, and onPanelRevealed(ZI)V,
 *   with its clearEffects false: opening the shade alone no longer stops the LED, sound and
 *   vibration of notifications nobody saw (CentralSurfacesImpl, panel expanded and shade revealed;
 *   NotificationManagerService.clearEffects). Optional: without them, Android clears as before;
 * - then, only with the first three, NotificationPanelViewController.updateExpansionAndVisibility()V,
 *   after: when an opening really starts (shadeOpening; onTrackingStarted and expand() call it
 *   before the first pixel), decided once (unlocked, no heads-up card pinned, the switch off), QS expanded with the panel (setExpandImmediate(true), the split shade's
 *   line), the stack hidden;
 *   once closed, the stack's visibility back to Android's. A heads-up card pinned over the closed
 *   shade is no opening: the stack that draws it stays visible.
 * Notifications still arrive: the pipeline, the Hub's listener, sounds, the LED and the heads-up
 * cards HeadsUp.kt keeps over the closed shade (calls, alarms, full screen intents) are untouched. Any missing piece, a failure or the
 * switch: Android's shade, notifications under its quick settings.
 */
internal fun installShade(
    loader: ClassLoader,
    fail: (String, Throwable?) -> Unit,
    after: (String, () -> Method, (Chain, Any?) -> Any?) -> Boolean,
    instead: (String, () -> Method, (Chain) -> Boolean) -> Boolean,
    withArgs: (String, () -> Method, (Chain) -> Array<Any?>?) -> Boolean,
) {
    val shade = try {
        ShadeHooks(loader)
    } catch (e: ReflectiveOperationException) {
        fail("hook target missing: shade, Android's notifications kept", e)
        return
    }
    shade.install(after, instead, withArgs)
}

private class ShadeHooks(loader: ClassLoader) {
    private val panel = loader.loadClass(PANEL_VIEW_CONTROLLER)
    private val updateExpansion = panel.getDeclaredMethod("updateExpansionAndVisibility")
    private val animateCollapseQs = panel.getDeclaredMethod("animateCollapseQs", Boolean::class.javaPrimitiveType)
    private val collapse = panel.getDeclaredMethod("collapse", Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val panelFraction = panel.getField("mExpandedFraction")
    private val panelTracking = panel.getField("mTracking")
    private val panelInstant = panel.getField("mInstantExpanding")
    private val panelAnimator = panel.getField("mHeightAnimator")
    private val panelHeadsUp = panel.getField("mHeadsUpManager")
    private val cardPinned = panelHeadsUp.type.getField("mHasPinnedNotification")
    private val panelBarState = panel.getField("mBarState")
    private val panelView = panel.getField("mView")
    private val panelQs = panel.getField("mQsController")
    private val panelStackController = panel.getField("mNotificationStackScrollLayoutController")
    private val stackView = panelStackController.type.getField("mView")
    private val stack = loader.loadClass(NOTIFICATION_STACK)
    private val updateVisibility = stack.getDeclaredMethod("updateVisibility")
    private val stackState = stack.getField("mStatusBarState")
    private val qs = loader.loadClass(QS_CONTROLLER)
    private val shouldIntercept = qs.getDeclaredMethod("shouldQuickSettingsIntercept", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    private val setExpandImmediate = qs.getDeclaredMethod("setExpandImmediate", Boolean::class.javaPrimitiveType)
    private val qsBarState = qs.getField("mBarState")
    private val qsExpanded = qs.getField("mExpanded")
    private val qsKeyguard = qs.getField("mKeyguardStateController")
    private val qsPanelView = qs.getField("mPanelView")
    private val keyguardShowing = loader.loadClass(KEYGUARD_STATE).getField("mShowing")

    /** Decided for this opening (shadeStep). SystemUI's main thread only. */
    private var decided = false
    /** This opening leaves the notifications out. Read too by onPanelRevealed, from SystemUI's background executor. */
    @Volatile private var alone = false
    /** KeyguardStateControllerImpl, a singleton, kept at the first decision for the stack's hook. */
    private var keyguard: Any? = null
    private var logged = false

    fun install(
        after: (String, () -> Method, (Chain, Any?) -> Any?) -> Boolean,
        instead: (String, () -> Method, (Chain) -> Boolean) -> Boolean,
        withArgs: (String, () -> Method, (Chain) -> Array<Any?>?) -> Boolean,
    ) {
        val hidden = after("$NOTIFICATION_STACK.updateVisibility()V + mStatusBarState", { updateVisibility }) { chain, result ->
            result.also { hide(chain.thisObject as View) }
        }
        val whole = after("$QS_CONTROLLER.shouldQuickSettingsIntercept(FFF)Z + mBarState, mExpanded, mShowing, mPanelView", { shouldIntercept }) { chain, intercepts ->
            val controller = chain.thisObject!!
            // On the lock screen, shut: its top pulls no quick settings.
            val lockedShut = keyguardShowing.getBoolean(qsKeyguard.get(controller)) &&
                shadeShut(androidShade((qsPanelView.get(controller) as View).context))
            qsIntercepts(intercepts as Boolean, aloneNow(qsBarState.getInt(controller), controller), qsExpanded.getBoolean(controller), lockedShut)
        }
        val back = instead("$PANEL_VIEW_CONTROLLER.animateCollapseQs(Z)V + collapse(FZZ)V, mBarState, mShowing", { animateCollapseQs }) { chain ->
            val controller = chain.thisObject!!
            aloneNow(panelBarState.getInt(controller), panelQs.get(controller)!!).also { if (it) collapse.invoke(controller, 1f, true, false) }
        }
        if (!hidden || !whole || !back) {
            Log.w(TAG, "shade: Android's notifications kept")
            return
        }
        // The LED and sounds of unseen notifications: lost on opening, as with Android, if these fail.
        // Found here, not in the constructor: a missing proxy method costs these two hooks only.
        val kept = instead("$STATUS_BAR_SERVICE_PROXY.clearNotificationEffects()V", {
            Class.forName(STATUS_BAR_SERVICE_PROXY).getDeclaredMethod("clearNotificationEffects")
        }) { alone }
        val revealed = withArgs("$STATUS_BAR_SERVICE_PROXY.onPanelRevealed(ZI)V", {
            Class.forName(STATUS_BAR_SERVICE_PROXY).getDeclaredMethod("onPanelRevealed", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }) { chain ->
            val clear = chain.getArg(0) as Boolean
            if (panelRevealedClears(clear, alone) == clear) null else arrayOf(false, chain.getArg(1))
        }
        if (!kept || !revealed) Log.w(TAG, "shade: opening it clears the notifications' LED and sound, as Android does")
        after("$PANEL_VIEW_CONTROLLER.updateExpansionAndVisibility()V + mExpandedFraction, mTracking, mInstantExpanding, mHeightAnimator, setExpandImmediate(Z)V", { updateExpansion }) { chain, result ->
            result.also { expansionChanged(chain.thisObject!!) }
        }
    }

    /** After Android updated the shade's expansion: decide when an opening really starts, undo once closed. */
    private fun expansionChanged(controller: Any) {
        val opening = shadeOpening(
            panelFraction.getFloat(controller),
            panelTracking.getBoolean(controller),
            panelInstant.getBoolean(controller),
            panelAnimator.get(controller) != null,
        )
        // Every frame of a pull: nothing to do while the opening goes on or the shade stays closed.
        if (opening == decided) return
        val before = ShadeStep(decided, alone)
        val next = shadeStep(before, opening) { decide(controller) }
        decided = next.decided
        alone = next.alone
        val effects = shadeEffects(before, next)
        if (effects.isEmpty()) return
        var done = false
        try {
            for (effect in effects) when (effect) {
                ShadeEffect.EXPAND_QS -> setExpandImmediate.invoke(panelQs.get(controller), true)
                ShadeEffect.UPDATE_VISIBILITY -> {
                    val view = stackView.get(panelStackController.get(controller)) as View
                    updateVisibility.invoke(view)
                    // hide() failed (counted by its hook) or let it be: the opening is Android's.
                    if (alone && view.visibility != View.INVISIBLE) alone = false
                }
            }
            done = true
        } finally {
            // A failure (counted by the hook) leaves Android's shade for this opening.
            if (!done) alone = false
        }
    }

    private fun decide(controller: Any): Boolean {
        val qs = panelQs.get(controller)!!
        keyguard = qsKeyguard.get(qs)
        val android = androidShade((panelView.get(controller) as View).context)
        if (android) Log.i(TAG, "shade: Android's notifications ($ANDROID_SHADE_SETTING=1)")
        // Read before HeadsUpTouchHelper unpins the card it pulls down (startExpandMotion, then unpinAll).
        val card = cardPinned.getBoolean(panelHeadsUp.get(controller))
        return shadeAlone(qsBarState.getInt(qs), keyguardShowing.getBoolean(keyguard), card, android)
    }

    /** Whether the shade is alone now, read from a QuickSettingsController's keyguard state. */
    private fun aloneNow(barState: Int, qsController: Any): Boolean =
        alone && shadeAloneNow(true, barState, keyguardShowing.getBoolean(qsKeyguard.get(qsController)))

    /** After Android set the stack's visibility: invisible while the shade is alone, unlocked, still SHADE. */
    private fun hide(stack: View) {
        if (!alone) return
        alone = shadeAloneNow(true, stackState.getInt(stack), keyguardShowing.getBoolean(keyguard))
        if (!alone) return
        stack.visibility = View.INVISIBLE
        if (!logged) {
            logged = true
            Log.i(TAG, "shade: quick settings alone, Android's notifications out of the shade")
        }
    }
}
