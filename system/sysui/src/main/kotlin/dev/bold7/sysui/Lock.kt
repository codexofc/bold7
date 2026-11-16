package dev.bold7.sysui

import dev.bold7.design.DialogDimens
import dev.bold7.design.HomeBanner
import dev.bold7.design.HomeDimens
import kotlin.math.ceil
import kotlin.math.roundToInt

// The 9900's lock screen and its password entry, pure. Sources in
// docs/BBOS7-VISUEL.md §10: lockscreen.pme and the theme's images (A), sim-64, sim-65 and the
// captures of the simulator's password entry, sim-85 to sim-89 (C), the guide (B); what the
// square or Android's flow changes is D.

// --- Lock screen ---------------------------------------------------------------------------

/**
 * The padlock, net_rim_KeyLockSystem.png (92x92, lockscreen.pme, A): its ink x 557 to 610, y 375
 * to 449 on sim-64, the image's ink starting at (19, 7): the image 10 px from the right edge and
 * 20 px from the bottom (C), kept from the Q25's corner (D).
 */
const val PADLOCK = "lock_padlock"
const val PADLOCK_RIGHT = 10
const val PADLOCK_BOTTOM = 20

/**
 * The owner's information (Options, Display, Message on Lock Screen, guide p. 24, B): OwnerInfo's
 * name (BBAlpha Sans Condensed bold 35, width 580) then info (bold 29, width 500, height 263),
 * white with a black outline at 60 % (lockscreen.pme, A). Android keeps one text: its first line is
 * the name, the rest the information (D). Place by eye on the CrackBerry photo: under the indicator
 * row, from the left, the name's box centred on the 9900's 640 (x 30).
 */
data class OwnerLines(val name: String?, val info: String?)

fun ownerLines(text: String?): OwnerLines {
    val lines = text.orEmpty().trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    return OwnerLines(lines.firstOrNull(), lines.drop(1).joinToString("\n").ifEmpty { null })
}

const val OWNER_X = 30
const val OWNER_NAME_WIDTH = 580
const val OWNER_INFO_WIDTH = 500
const val OWNER_INFO_HEIGHT = 263

/** The banner and its indicator row, 93 + 47 px. */
const val HOME_BANNER_HEIGHT = HomeDimens.BANNER + HomeDimens.INDICATOR_ROW

/** By eye (CrackBerry photo): the name's top 10 px under the indicator row, the information right under it. */
const val OWNER_TOP = HOME_BANNER_HEIGHT + 10

/** The outline: stroke=000000&stroke-opacity=0.6 (A), 2 px wide by eye. */
const val OWNER_STROKE = 2f
const val OWNER_STROKE_COLOR = 0x99000000.toInt()

/**
 * The setting where the launcher, which keeps BBOS 7's profiles (ProfileController),
 * publishes the one in use, by its id (Profile.id: normal, vibrate, silent, loud, phoneonly,
 * alertsoff, medium). Settings.Global: any process reads it without a permission, only the
 * launcher (WRITE_SECURE_SETTINGS, a priv-app) writes it.
 */
const val PROFILE_SETTING = "bold7_profile"

/** The frames of net_rim_Ribbon_Profile-icons43x43 by profile id, as the home shows them (Profile.frame of the launcher). */
private val PROFILE_IMAGES = mapOf(
    "normal" to "lock_profile_normal",
    "vibrate" to "lock_profile_vibrate",
    "silent" to "lock_profile_silent",
    "loud" to "lock_profile_loud",
    "phoneonly" to "lock_profile_phone_only",
    "alertsoff" to "lock_profile_alerts_off",
    "medium" to "lock_profile_medium",
)

/**
 * The indicator row's speaker, a whole frame of net_rim_Ribbon_Profile-icons43x43 (A): the profile
 * the launcher says is in use ([profile], [PROFILE_SETTING]), as the home shows it (sim-308 for
 * Medium, sim-337 for Normal). Without one (the launcher not yet started, an unknown id), Android's
 * ringer mode, which tells Normal, Vibrate Only and Silent apart only.
 * AudioManager.RINGER_MODE_SILENT 0, _VIBRATE 1, _NORMAL 2.
 */
fun lockProfileImage(profile: String?, ringerMode: Int): String = PROFILE_IMAGES[profile] ?: when (ringerMode) {
    0 -> "lock_profile_silent"
    1 -> "lock_profile_vibrate"
    else -> "lock_profile_normal"
}

/**
 * The counters of the indicator row, as on the home (sim-64, sim-03, C): each number then its icon
 * [HomeBanner.COUNT_GAP] after it, [HomeBanner.INDICATOR_GAP] between two, the whole centred.
 * [numbers] the numbers' advances, [icons] the icons' widths; each (number's x, icon's x).
 */
fun rowCounterXs(width: Int, numbers: List<Int>, icons: List<Int>): List<Pair<Int, Int>> {
    val total = numbers.indices.sumOf { numbers[it] + HomeBanner.COUNT_GAP + icons[it] } + HomeBanner.INDICATOR_GAP * (numbers.size - 1)
    var x = (width - total) / 2
    return numbers.indices.map { i ->
        val number = x
        x += numbers[i] + HomeBanner.COUNT_GAP
        val icon = x
        x += icons[i] + HomeBanner.INDICATOR_GAP
        number to icon
    }
}

/** Every image the lock screen draws, SystemUI's own (tools/import-assets.sh systeme). */
val LOCK_IMAGES = listOf(
    "lock_banner_background", "lock_banner_divider", "lock_indicator_bar",
    *PROFILE_IMAGES.values.toTypedArray(), PADLOCK, EMERGENCY_ICON,
)

/** The images of core/ui the password entry draws (tools/import-assets.sh ui). */
val BOUNCER_UI_IMAGES = listOf("ui_popup", "ui_find", "ui_dialog_exclamation", "ui_button", "ui_button_focus")

// --- Password entry ------------------------------------------------------------------------

/**
 * What Android's message areas say, the old one and the two of BouncerMessageView, OS 7's way:
 * its prompts ("Enter your PIN", "Enter PIN") left out, OS 7 writing its own "Enter Password:";
 * whatever else Android says (wrong code, a delay, why the code is needed), its lines together,
 * goes into OS 7's dialog with the exclamation mark (sim-87). Null: nothing but a prompt.
 */
fun bouncerText(texts: List<CharSequence?>, prompts: Collection<CharSequence>): String? {
    val skip = prompts.map { it.toString().trim() }.toSet()
    return texts.flatMap { it?.toString()?.lines().orEmpty() }
        .map(String::trim)
        .filter { it.isNotEmpty() && it !in skip }
        .distinct()
        .joinToString("\n")
        .ifEmpty { null }
}

/** Which box the cover shows: OS 7's error while Android has [message] and nothing is typed again (sim-87), else the entry (sim-85). */
enum class BouncerBox { ENTRY, ERROR }

fun bouncerBox(message: String?, typed: Int): BouncerBox = if (message != null && typed == 0) BouncerBox.ERROR else BouncerBox.ENTRY

/** Android's "Wrong PIN" or "Wrong password" in OS 7's words, "Incorrect password!" (sim-87, C); anything else as Android says it. */
fun errorText(message: String, wrong: Collection<CharSequence>, incorrect: String): String =
    if (wrong.any { it.toString().trim() == message.trim() }) incorrect else message

/** One asterisk a character Android holds, never the characters (sim-86, C), as many as the field holds. */
fun maskedEntry(count: Int, max: Int): String = "*".repeat(count.coerceIn(0, max.coerceAtLeast(0)))

/**
 * The entry, a dialog box of OS 7 with a label and a field (sim-85, and sim-61 of Options, C:
 * the same box), then, when Android offers its emergency call, a separator and the Emergency
 * button of sim-65 (D: the 9900 shows "Device is locked" with Unlock, Cancel and Emergency first,
 * Android its entry at once). In px: x from the cover's left (the bouncer's view, at most
 * [coverWidth] wide), y on the screen. sim-85: the box x 36 to 603 and y 170 to 304 on 640x480,
 * centred like every dialog (2 px above the middle).
 */
class PasswordBox(coverWidth: Int, screenWidth: Int, screenHeight: Int, val emergency: Boolean, emergencyLabel: Float) {
    val width: Int = minOf(coverWidth, screenWidth - 2 * MARGIN)
    val left: Int = (coverWidth - width) / 2
    val height: Int = if (emergency) EMERGENCY_HEIGHT else HEIGHT
    val top: Int = (screenHeight - height - 4) / 2
    val right: Int get() = left + width

    val labelX = left + LABEL_X
    val labelBaseline = top + LABEL_BASELINE

    /** border_find.png as a 9-patch, its 2 px halo included (A). */
    val fieldLeft = left + FIELD_X
    val fieldTop = top + FIELD_TOP
    val fieldRight = right - FIELD_X
    val fieldBottom = fieldTop + FIELD_HEIGHT

    val textX = left + TEXT_X
    val textBaseline = top + TEXT_BASELINE
    val caretTop = top + CARET_TOP

    /** How many asterisks fit before the field's end (its last 20 px kept clear, D), the caret after them. */
    fun maskMax(advance: Float): Int = if (advance <= 0f) 0 else ((fieldRight - FIELD_X - CARET_WIDTH - textX) / advance).toInt()

    val separatorY = top + SEPARATOR
    val separatorLeft = left + SEPARATOR_INSET
    val separatorRight = right - SEPARATOR_INSET

    /** The Emergency button, DialogLayout's rule: 0.545 of the box, or its label and 20 px each side if wider. */
    val buttonWidth: Int = minOf(
        width - 2 * DialogDimens.BUTTON_PAD,
        maxOf((width * DialogDimens.BUTTON_RATIO).roundToInt(), ceil(emergencyLabel).toInt() + 2 * DialogDimens.BUTTON_PAD),
    )
    val buttonLeft = left + (width - buttonWidth) / 2
    val buttonTop = top + BUTTON

    fun onButton(x: Float, y: Float): Boolean =
        emergency && x >= buttonLeft && x < buttonLeft + buttonWidth && y >= buttonTop && y < buttonTop + DialogDimens.BUTTON_HEIGHT

    companion object {
        /** sim-85: the screen less 36 px on each side (D on the Q25: 720 less the same, 648). */
        const val MARGIN = 36

        /** Border pixel to border pixel, 170 to 304 (sim-85). */
        const val HEIGHT = 135
        const val LABEL_X = 21 // "Enter Password:" ink from x 59
        const val LABEL_BASELINE = 52 // its capitals y 202 to 221
        const val FIELD_X = 20 // the field's dark ring x 58 to 581
        const val FIELD_TOP = 58 // its ring from y 230, the image's third row
        const val FIELD_HEIGHT = 56 // its ring to y 281, then 2 rows of halo
        const val TEXT_X = 33 // the caret, then the asterisks, from x 69
        const val TEXT_BASELINE = 95 // the caret's top 25 px above it, as a line of 27 px text
        const val CARET_TOP = 70 // y 240 to 271
        const val CARET_HEIGHT = 32
        const val CARET_WIDTH = 9 // x 69 to 77

        /**
         * sim-65: the separator, #878787, 21 px under the button above it and 60 px from the box's
         * sides; Emergency 21 px under it; the box's bottom border 22 px under that button.
         */
        const val SEPARATOR = 111 + 21 // the field's ring ends at y 281, 111 under the box's top
        const val SEPARATOR_INSET = 60
        const val SEPARATOR_COLOR = 0xFF878787.toInt()
        const val BUTTON = SEPARATOR + 21
        const val EMERGENCY_HEIGHT = BUTTON + DialogDimens.BUTTON_HEIGHT + 22

        /** From the handset (phone_emergency-icons32x32, A) to the label: none, the image's own margin (sim-65, by eye). */
        const val ICON_GAP = 0
    }
}

/** The handset of the Emergency button (sim-65): phone_emergency-icons32x32.png (A). */
const val EMERGENCY_ICON = "lock_emergency"

/**
 * Whether OS 7's entry covers Android's bouncer: the 9900 had no pad, its password was typed on the
 * keyboard. Only with a keyboard at hand (Configuration.keyboard KEYBOARD_QWERTY,
 * hardKeyboardHidden not HARDKEYBOARDHIDDEN_YES), or the code could not be typed at all.
 */
fun padCovered(keyboard: Int, hardKeyboardHidden: Int): Boolean = keyboard == 2 && hardKeyboardHidden != 2

/**
 * The bouncer views OS 7's entry covers, by their exact class: the device's PIN and password, and
 * MediaTek's view of the SIM's PIN and PUK (KeyguardSimPinPukMeView: the only one
 * KeyguardSecurityViewFlipperController inflates for the SIM on the Q25). Never AOSP's SIM views
 * (KeyguardSimPinView, KeyguardSimPukView, never inflated here), MediaTek's anti-theft lock, nor
 * the pattern.
 */
fun bouncerDressed(viewClass: String): Boolean =
    viewClass == KEYGUARD_PIN_VIEW || viewClass == KEYGUARD_PASSWORD_VIEW || viewClass == KEYGUARD_SIM_VIEW

/**
 * The key code Android's PIN view should get for a key of the Q25's keyboard. Its digits sit on
 * letters (Q25_keyboard.kcm: W 1, E 2, R 3, S 4, D 5, F 6, Z 7, X 8, C 9, with their `number:`
 * property; Alt gives the same digits), and KeyguardPinBasedInputView.onKeyDown takes only
 * KEYCODE_0 to 9 and the numeric keypad: a letter key whose number is a digit becomes that digit's
 * key, as Android's own numeric fields read it (DigitsKeyListener, KeyEvent.getNumber). Null:
 * Android's key as it came (digits, delete, enter, the rest).
 */
fun pinKeyCode(keyCode: Int, number: Char, repeat: Int): Int? {
    // A held letter types one digit: its repeats go on as letters, which no PIN view takes.
    if (repeat > 0) return null
    if (keyCode in KEYCODE_0..KEYCODE_9 || keyCode in KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9) return null
    return if (number in '0'..'9') KEYCODE_0 + (number - '0') else null
}

// KeyEvent.KEYCODE_0, _9, _NUMPAD_0, _NUMPAD_9.
private const val KEYCODE_0 = 7
private const val KEYCODE_9 = 16
private const val KEYCODE_NUMPAD_0 = 144
private const val KEYCODE_NUMPAD_9 = 153

/**
 * Whether [pinKeyCode] applies to the bouncer view of this class. onKeyDown is final in
 * KeyguardPinBasedInputView, which the SIM's views and MediaTek's anti-theft lock share. The
 * device's PIN (KeyguardPINView); and MediaTek's SIM view only while OS 7's box covers it
 * ([simCovered]): its pad is then hidden and the keyboard is the only way to type, as
 * on the 9900, whose number fields take the digits of the letter keys. A digit typed is one press
 * of Android's own key (NumPadKey.performClick), nothing is sent: only Enter and the trackpad's
 * click, Android's confirm keys, untouched, have the code checked, and Android refuses a SIM PIN
 * shorter than 4 digits without counting an attempt (validatePin). Uncovered (the box given back,
 * no keyboard, a failure), the SIM's screen is Android's as before: a letter types nothing.
 */
fun pinKeysTranslated(viewClass: String, simCovered: Boolean): Boolean =
    viewClass == KEYGUARD_PIN_VIEW || (viewClass == KEYGUARD_SIM_VIEW && simCovered)

/**
 * What the SIM's view asks for, by KeyguardSimPinPukMeViewController.mUnlockEnterState (MtkSystemUI
 * of the Q25, jadx): 0 the PIN, 1 the PUK, 2 a new PIN, 3 its confirmation, 4 the PUK and the new
 * PIN sent (Android's progress dialog over the box until the modem answers: the confirmation's
 * label stays). Null, Android's screen back: 5, MediaTek's network lock, which this lot does not
 * draw (its Dismiss button would be hidden), and anything unknown.
 */
enum class SimEntry { PIN, PUK, NEW_PIN, CONFIRM_PIN }

fun simEntry(state: Int?): SimEntry? = when (state) {
    0 -> SimEntry.PIN
    1 -> SimEntry.PUK
    2 -> SimEntry.NEW_PIN
    3, 4 -> SimEntry.CONFIRM_PIN
    else -> null
}

// --- Place Call -----------------------------------------------------------

/**
 * The Call key as the lock screen's window sees it (guide 7.1 p. 61, B: "If your smartphone is
 * locked [...] press the Send key > Place Call"). [released] is true on the release of a press it
 * saw from its start, never on a release alone: PhoneWindowManager answers a ringing call on the
 * press and keeps that press from every window, not the release (services.jar of the Q25, AOSP
 * 14), and that release must open nothing. A canceled press opens nothing either. Main thread only.
 */
class CallKeyPress {
    private var down: Long? = null

    fun released(action: Int, keyCode: Int, repeat: Int, downTime: Long, canceled: Boolean): Boolean {
        if (keyCode != KEYCODE_CALL) return false
        if (action == ACTION_DOWN) {
            if (repeat == 0) down = downTime
            return false
        }
        val seen = down == downTime
        down = null
        return action == ACTION_UP && seen && !canceled
    }
}

/**
 * Whether the Call key opens Place Call: on the lock screen itself ([state] KEYGUARD, [showing],
 * not [occluded] by an app shown over it, the shade not pulled), awake, no bouncer up (the device's
 * PIN or password, the SIM's PIN or PUK), no call of any kind ([inCall]: ringing, dialing, active or
 * held, Telecom's), and nothing [playing] that is a ring, an alarm or a voice call (AudioAttributes
 * usages: an alarm ringing before its screen comes up, another app's call outside Telecom).
 */
fun placeCallOpens(state: Int, showing: Boolean, occluded: Boolean, bouncer: Boolean, interactive: Boolean, inCall: Boolean, playing: Collection<Int>): Boolean =
    state == STATE_KEYGUARD && showing && !occluded && !bouncer && interactive && !inCall && playing.none { it in BUSY_USAGES }

// AudioAttributes.USAGE_VOICE_COMMUNICATION 2, USAGE_ALARM 4, USAGE_NOTIFICATION_RINGTONE 6.
private val BUSY_USAGES = setOf(2, 4, 6)

// KeyEvent.KEYCODE_CALL, ACTION_DOWN, ACTION_UP.
private const val KEYCODE_CALL = 5
private const val ACTION_DOWN = 0
private const val ACTION_UP = 1
