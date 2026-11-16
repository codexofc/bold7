package dev.bold7.sysui

// When the lock screen's face of OS 7 stands in for Android's, pure.

/** StatusBarState.KEYGUARD of SystemUI: the lock screen itself (SHADE 0, KEYGUARD 1, SHADE_LOCKED 2). */
const val STATE_KEYGUARD = 1

/**
 * Whether SystemUI is awake, read from the stack's AmbientState: not dozing, no doze amount
 * left. Not the display state: DozeScreenState turns the display fully on while it pulses a
 * notification (DOZE_PULSING, DOZE_PULSING_BRIGHT, DOZE_REQUEST_PULSE), still dozing.
 */
fun awake(dozing: Boolean, dozeAmount: Float): Boolean = !dozing && dozeAmount == 0f

/**
 * Whether Android's notification stack goes undrawn and untouchable: OS 7's face in place, on the
 * lock screen itself (pulled down, the shade shows it again), [awake] (the always-on display and
 * its pulses stay Android's, the notification that wakes the phone included).
 */
fun stackHidden(state: Int, awake: Boolean, faceReady: Boolean): Boolean = faceReady && state == STATE_KEYGUARD && awake

/**
 * The face's opacity: Android's own for its bottom area's content (KeyguardBottomAreaViewBinder
 * fades the children, keyguard_indication_area among them, not the area itself), on the lock
 * screen itself, [awake]; whole while OS 7's entry shows over it ([bouncerShown]: sim-85 keeps the
 * lock screen as sim-64 under "Enter Password:", where Android fades its own out); none anywhere
 * else.
 */
fun faceAlpha(indicationAlpha: Float, state: Int, awake: Boolean, bouncerShown: Boolean): Float = when {
    state != STATE_KEYGUARD || !awake -> 0f
    bouncerShown -> 1f
    else -> indicationAlpha.coerceIn(0f, 1f)
}

/**
 * Whether ScrimController's scrims go clear (behind and notifications, by ScrimState's [state]
 * name), so that the wallpaper shows whole as on the 9900 (sim-64, sim-85: the wallpaper's pixels
 * under the entry are those of the lock screen): on the lock screen itself under OS 7's face,
 * where Android darkens it by 20 % (KEYGUARD, mScrimBehindAlphaKeyguard), unless the shade is being
 * pulled over it ([qsExpansion], [fullShadeProgress]: Android's darkening for its shade); under
 * OS 7's entry ([bouncerDressed]), where Android puts its black (BOUNCER). The SIM's PIN and every
 * bouncer OS 7 does not dress keep Android's black, as does Android's face.
 */
fun scrimsCleared(state: String, faceReady: Boolean, bouncerDressed: Boolean, qsExpansion: Float, fullShadeProgress: Float): Boolean =
    faceReady && when (state) {
        "KEYGUARD" -> qsExpansion == 0f && fullShadeProgress == 0f
        "BOUNCER" -> bouncerDressed
        else -> false
    }

/**
 * What the hidden stack answers to a touch event, decided once at ACTION_DOWN from what the last
 * frame showed ([hiddenAtDown]) and kept for the whole gesture: [intercept] for
 * onInterceptTouchEvent (true: no row gets it), for onTouchEvent (false: the stack keeps none, it
 * goes on to the panel). Null: Android's own answer, always for ACTION_CANCEL.
 */
fun stackTouchAnswer(action: Int, hiddenAtDown: Boolean, intercept: Boolean): Boolean? =
    if (action == ACTION_CANCEL || !hiddenAtDown) null else intercept

// MotionEvent.ACTION_CANCEL.
private const val ACTION_CANCEL = 3
