package dev.bold7.sysui

// The shade without Android's notifications: BBOS 7 has no notification shade, its notifications drop from the
// home's banner (docs/BBOS7-VISUEL.md §5, the launcher's list). Android's quick settings stay alone
// in the shade, the toggles until Manage Connections. Pure; the hooks are ShadeHooks, and the home's bar is StatusBarLayout's.

/**
 * The emergency switch, a global setting SystemUI reads at each opening of the shade: 1 gives
 * Android's notification shade back (`adb shell settings put global bold7_android_shade 1`), and
 * its status bar on the home, anything else, or no setting, keeps BBOS 7's.
 */
const val ANDROID_SHADE_SETTING = "bold7_android_shade"

fun androidShadeAsked(value: Int): Boolean = value == 1

// StatusBarState.SHADE.
private const val SHADE = 0

/**
 * Whether an opening of the shade leaves Android's notifications out: unlocked ([barState]
 * SHADE, no keyguard showing, not even under an app shown over it), no heads-up card pinned
 * ([cardPinned]), unless the switch asks for Android's shade. Never an empty shade: Android's
 * quick settings stay. Locked, Android's. A card pinned when the opening starts (a swipe down on an
 * incoming call or an alarm, HeadsUpTouchHelper) is unpinned by that opening for good: hidden with
 * the list, it would not come back once closed, Answer left to the Phone app only. With a card,
 * Android's shade, the card in its list.
 */
fun shadeAlone(barState: Int, locked: Boolean, cardPinned: Boolean, androidShade: Boolean): Boolean =
    barState == SHADE && !locked && !cardPinned && !androidShade

/**
 * Whether the shade is really being opened or open (NotificationPanelViewController, after
 * updateExpansionAndVisibility): pulled down ([fraction] above 0), followed by a finger
 * ([tracking], from onTrackingStarted on, before the first pixel), expanding at once (expand()) or
 * animated. Not Android's isExpanded(), which also holds for a heads-up card pinned over the
 * closed shade (STATE_OPENING with nothing pulled down): that card is drawn by the stack and
 * must stay visible and touchable.
 */
fun shadeOpening(fraction: Float, tracking: Boolean, instantExpanding: Boolean, heightAnimating: Boolean): Boolean =
    fraction > 0f || tracking || instantExpanding || heightAnimating

/** Where an opening stands: [decided] once it really started, [alone] its decision. */
data class ShadeStep(val decided: Boolean, val alone: Boolean)

/**
 * The step after Android updated the shade's expansion: decided once, when an opening really
 * starts ([decide], asked then only), kept while it lasts, dropped once the shade is closed.
 */
fun shadeStep(current: ShadeStep, opening: Boolean, decide: () -> Boolean): ShadeStep = when {
    !opening -> ShadeStep(decided = false, alone = false)
    current.decided -> current
    else -> ShadeStep(decided = true, alone = decide())
}

enum class ShadeEffect { EXPAND_QS, UPDATE_VISIBILITY }

/**
 * What a step asks of Android, in order: an opening without notifications first expands QS with
 * the panel (setExpandImmediate, the split shade's line), then has the stack's visibility set
 * again (now INVISIBLE); a closing always gives the stack's visibility back to Android, whatever
 * the opening was (a failed one may have left it INVISIBLE).
 */
fun shadeEffects(before: ShadeStep, after: ShadeStep): List<ShadeEffect> = when {
    after.alone && !before.alone -> listOf(ShadeEffect.EXPAND_QS, ShadeEffect.UPDATE_VISIBILITY)
    before.decided && !after.decided -> listOf(ShadeEffect.UPDATE_VISIBILITY)
    else -> emptyList()
}

/**
 * Whether the decision applies now: only while the bar state is still SHADE and no keyguard
 * shows. The panel stays expanded from the shade to the keyguard (screen off), and the keyguard
 * shows before the bar state changes (KeyguardViewMediator.handleShow, then resetViews).
 */
fun shadeAloneNow(alone: Boolean, barState: Int, locked: Boolean): Boolean = alone && barState == SHADE && !locked

/**
 * QuickSettingsController.shouldQuickSettingsIntercept: Android's answer, but never while QS is
 * expanded in a shade without notifications, as in Android's split shade: QS no longer folds into
 * its quick row on its own (which would uncover the emptied list), the panel moves as a whole.
 * Never either on the lock screen with the shade shut ([lockedShut]): a pull
 * on its top brings no quick settings.
 */
fun qsIntercepts(android: Boolean, aloneNow: Boolean, qsExpanded: Boolean, lockedShut: Boolean): Boolean =
    android && !lockedShut && !(aloneNow && qsExpanded)

/**
 * Whether a gesture or a request that would open Android's shade does nothing:
 * OS 7 opens its notifications and Manage Connections from the home's banner only ("Highlight and
 * click the areas at the top of the home screen", guide 7.1 p. 33 and 44, B), nothing from the bar of an
 * app (docs/BBOS7-VISUEL.md §18) nor from the lock screen. Always shut, unless the switch asks for
 * Android's shade ([androidShade]).
 */
fun shadeShut(androidShade: Boolean): Boolean = !androidShade

/**
 * What PhoneStatusBarViewController's touch handler answers in place of Android's, for a gesture
 * whose ACTION_DOWN found the shade [shutAtDown]: eaten (true), never handed to the shade; null,
 * Android's (the shade follows the finger).
 */
fun statusBarTouchAnswer(shutAtDown: Boolean): Boolean? = if (shutAtDown) true else null

/**
 * LockscreenShadeTransitionController.isDragDownEnabledForView: Android's answer ([android]) only
 * with the switch on; shut, no drag down from the lock screen to its shade (DragDownHelper).
 */
fun dragDownEnabled(android: Boolean, androidShade: Boolean): Boolean = android && !shadeShut(androidShade)

/**
 * IStatusBarService.onPanelRevealed's clearEffects: Android's, but never for a shade without
 * notifications. Opening it would otherwise stop the LED, sound and vibration of notifications
 * nobody saw (NotificationManagerService.clearEffects): the 9900's red LED flashes until the message
 * is opened (guide 7.1, p. 15, M).
 */
fun panelRevealedClears(clearEffects: Boolean, alone: Boolean): Boolean = clearEffects && !alone

/** The home, where the launcher draws the 9900's banner and hides Android's bar. */
const val LAUNCHER_PACKAGE = "dev.bold7.launcher"

/**
 * Whether Android's status bar stays out of sight, the window that sets its look being
 * [controllingPackage] (CommandQueue.onSystemBarAttributesChanged): on the home, where it would
 * show over the banner for a swipe from the top (Android's transient bars), a heads-up card or a
 * system event, and over an app that has no such bar on the 9900 ([BarKind.NONE]), where it would
 * show over the app's own title for a swipe or a dialog; and under the lock screen ([keyguard], the
 * window being SystemUI's), which draws its own bar: kept shown there, the apps' bar was drawn over
 * the home's banner for the frames the lock screen took to go once unlocked, before the launcher's
 * window set the bar's look. Anywhere else, or with the switch on, Android's.
 */
fun barHidden(controllingPackage: String?, androidShade: Boolean, keyguard: Boolean): Boolean =
    !androidShade && (
        controllingPackage == LAUNCHER_PACKAGE || barKind(controllingPackage) == BarKind.NONE ||
            keyguard && controllingPackage == "com.android.systemui"
        )

/**
 * Whether the bar stays exactly as it was when the window that sets its look is SystemUI's own
 * ([controllingPackage]) while the power key's dialog shows ([powerMenu]): that dialog lies over the
 * home or an app and changes nothing of the bar under it. Read as SystemUI's (the keyguard's look:
 * no background, Android's bar shown), it put Android's bar, transparent, time and all, over the
 * home's banner whenever the window manager showed it during the dialog.
 */
fun barKept(controllingPackage: String?, powerMenu: Boolean): Boolean = powerMenu && controllingPackage == "com.android.systemui"

// WindowInsets.Type.statusBars(), a constant of the framework since Android 11.
private const val STATUS_BARS = 1

/**
 * After SystemUI showed transient bars ([types] on display [displayId]): the status bar is hidden
 * again at once where it is hidden ([hidden], barHidden). Drawn transparent there, it would
 * still take the touches of the banner's top for its seconds of transience (and pull the shade).
 */
fun transientUndone(hidden: Boolean, displayId: Int, types: Int): Boolean =
    hidden && displayId == 0 && types and STATUS_BARS != 0
