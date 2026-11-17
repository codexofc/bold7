package dev.bold7.sysui

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LockTest {
    // --- Lock screen --------------------------------------------------------------------------

    // Android keeps one text: its first line is OwnerInfo's name, the rest its information.
    @Test fun ownerInfoSplitsIntoNameAndInformation() {
        assertEquals(OwnerLines("CrackBerry Adam", "If found please call me!"), ownerLines("CrackBerry Adam\nIf found please call me!"))
        assertEquals(OwnerLines("Alice", null), ownerLines("  Alice  "))
        assertEquals(OwnerLines("Alice", "a\nb"), ownerLines("Alice\n\n a \nb"))
        assertEquals(OwnerLines(null, null), ownerLines(null))
        assertEquals(OwnerLines(null, null), ownerLines(" \n "))
    }

    // Options' Message on Lock Screen writes what the lock screen reads back.
    @Test fun ownerTextRoundTrips() {
        assertEquals(OwnerLines("Sam", "If found, call 555-0100"), ownerLines(ownerText(" Sam ", "If found, call 555-0100")))
        assertEquals("Sam", ownerText("Sam", "  "))
        assertEquals("", ownerText("", ""))
        assertEquals(OwnerLines("Sam", "a\nb"), ownerLines(ownerText("Sam", "a\nb")))
    }

    // The profile the launcher publishes, as the home shows it: Medium's speaker with one yellow
    // wave (sim-308), Normal's (sim-337), and every other profile's frame, whatever Android's ringer.
    @Test fun speakerIsTheProfileInUse() {
        assertEquals("bold7_profile", PROFILE_SETTING)
        assertEquals("lock_profile_medium", lockProfileImage("medium", ringerMode = 2))
        assertEquals("lock_profile_loud", lockProfileImage("loud", ringerMode = 2))
        assertEquals("lock_profile_phone_only", lockProfileImage("phoneonly", ringerMode = 2))
        assertEquals("lock_profile_alerts_off", lockProfileImage("alertsoff", ringerMode = 2))
        assertEquals("lock_profile_silent", lockProfileImage("silent", ringerMode = 2))
        assertEquals("lock_profile_normal", lockProfileImage("normal", ringerMode = 0))
    }

    // Without the launcher's word (not started yet, an unknown id), Android's ringer mode:
    // AudioManager.RINGER_MODE_SILENT 0, _VIBRATE 1, _NORMAL 2.
    @Test fun speakerFallsBackOnTheRingerMode() = assertEquals(
        listOf("lock_profile_silent", "lock_profile_vibrate", "lock_profile_normal", "lock_profile_normal"),
        listOf(0, 1, 2, 99).map { lockProfileImage(if (it == 99) "custom" else null, it) },
    )

    // sim-64 (as sim-03): "2" then the missed call's icon 6 px after it, centred on the row.
    @Test fun rowCountersAreCentredWithTheHomesGaps() {
        assertEquals(listOf(296 to 310), rowCounterXs(640, listOf(8), listOf(33)))
        assertEquals(listOf(268 to 282, 325 to 339), rowCounterXs(640, listOf(8, 8), listOf(33, 33)))
        assertEquals(emptyList(), rowCounterXs(720, emptyList(), emptyList()))
    }

    // --- Password entry -----------------------------------------------------------------------

    // sim-85, 640x480: the box x 36 to 603, y 170 to 304; "Enter Password:" from x 59, capitals
    // y 202 to 221; the field's ring x 58 to 581, y 230 to 281; the caret x 69 to 77, y 240 to 271.
    @Test fun entryBoxIsTheSimulatorsOne() {
        val b = PasswordBox(coverWidth = 640, screenWidth = 640, screenHeight = 480, emergency = false, emergencyLabel = 0f)
        assertEquals(listOf(36, 603, 170, 304), listOf(b.left, b.right - 1, b.top, b.top + b.height - 1))
        assertEquals(57 to 222, b.labelX to b.labelBaseline)
        assertEquals(listOf(56, 228, 584, 284), listOf(b.fieldLeft, b.fieldTop, b.fieldRight, b.fieldBottom))
        assertEquals(69 to 240, b.textX to b.caretTop)
        assertEquals(240 + PasswordBox.CARET_HEIGHT - 1, 271)
        assertFalse(b.onButton(320f, 300f))
    }

    // sim-65: the separator 21 px under the last button's ring, Emergency 21 px under it, 51 high,
    // the box ending 22 px under it; the box centred as every dialog.
    @Test fun emergencyHangsUnderTheFieldAsOnDeviceIsLocked() {
        val b = PasswordBox(coverWidth = 546, screenWidth = 720, screenHeight = 720, emergency = true, emergencyLabel = 160f)
        assertEquals(546, b.width)
        assertEquals(0, b.left)
        assertEquals(b.top + 111 + 21, b.separatorY)
        assertEquals(b.separatorY + 21, b.buttonTop)
        assertEquals(b.buttonTop + 51 + 22, b.top + b.height)
        assertEquals((720 - b.height - 4) / 2, b.top)
        assertEquals(listOf(60, 486), listOf(b.separatorLeft, b.separatorRight))
        // 0.545 of the box, wider than the label and its 20 px.
        assertEquals(298, b.buttonWidth)
        assertTrue(b.onButton(273f, b.buttonTop + 10f))
        assertFalse(b.onButton(273f, b.buttonTop - 1f))
        assertFalse(b.onButton(10f, b.buttonTop + 10f))
    }

    // On the Q25 without a narrower bouncer: the screen less 36 px on each side, centred.
    @Test fun entryBoxKeepsTheSimulatorsMarginsOnTheSquare() {
        val b = PasswordBox(coverWidth = 720, screenWidth = 720, screenHeight = 720, emergency = false, emergencyLabel = 0f)
        assertEquals(648 to 36, b.width to b.left)
    }

    @Test fun maskFitsTheField() {
        val b = PasswordBox(coverWidth = 640, screenWidth = 640, screenHeight = 480, emergency = false, emergencyLabel = 0f)
        // 14 px a "*" (sim-86: x 75, 89, 103).
        assertEquals(34, b.maskMax(14f))
        assertEquals(0, b.maskMax(0f))
        assertEquals("***", maskedEntry(3, 34))
        assertEquals("*".repeat(34), maskedEntry(100, 34))
        assertEquals("", maskedEntry(-1, 34))
    }

    private val prompts = listOf("Enter your PIN", "Enter PIN", "Enter your password")
    private val wrong = listOf("Wrong PIN", "Wrong password")

    // Android's prompts are OS 7's label, never an error; the rest of what its areas say is.
    @Test fun messageAreasReadWithoutAndroidsPrompts() {
        assertEquals(null, bouncerText(listOf("Enter your PIN", null, " "), prompts))
        assertEquals(null, bouncerText(listOf(" Enter your password ", "Enter PIN"), prompts))
        assertEquals("Wrong PIN", bouncerText(listOf("Wrong PIN", null, "Wrong PIN"), prompts))
        assertEquals("PIN is required after device restarts", bouncerText(listOf(null, "Enter PIN", "PIN is required after device restarts"), prompts))
        assertEquals("Wrong PIN\nTry again in 30 seconds.", bouncerText(listOf(null, "Wrong PIN", "Try again in 30 seconds."), prompts))
    }

    // The error box until a key is typed again: OS 7's OK, here the next character.
    @Test fun errorBoxShowsUntilTypingResumes() {
        assertEquals(BouncerBox.ERROR, bouncerBox("Wrong PIN", typed = 0))
        assertEquals(BouncerBox.ENTRY, bouncerBox("Wrong PIN", typed = 1))
        assertEquals(BouncerBox.ENTRY, bouncerBox(null, typed = 0))
    }

    @Test fun wrongCodeIsOs7sIncorrectPasswordAndTheRestAndroids() {
        assertEquals("Incorrect password!", errorText("Wrong PIN", wrong, "Incorrect password!"))
        assertEquals("Incorrect password!", errorText(" Wrong password", wrong, "Incorrect password!"))
        assertEquals("Try again in 30 seconds.", errorText("Try again in 30 seconds.", wrong, "Incorrect password!"))
    }

    // Configuration.KEYBOARD_QWERTY = 2, KEYBOARD_NOKEYS = 1; HARDKEYBOARDHIDDEN_NO = 1, _YES = 2.
    @Test fun coveredOnlyWithAKeyboardAtHand() {
        assertTrue(padCovered(keyboard = 2, hardKeyboardHidden = 1))
        assertFalse(padCovered(keyboard = 1, hardKeyboardHidden = 1))
        assertFalse(padCovered(keyboard = 2, hardKeyboardHidden = 2))
    }

    @Test fun aCodeIsCoveredOnlyWithAKeyboard() {
        assertTrue(bouncerCovered(KEYGUARD_PIN_VIEW, keyboard = 2, hardKeyboardHidden = 1))
        assertFalse(bouncerCovered(KEYGUARD_PIN_VIEW, keyboard = 1, hardKeyboardHidden = 1))
        assertFalse(bouncerCovered(KEYGUARD_SIM_PIN_VIEW, keyboard = 2, hardKeyboardHidden = 2))
        assertFalse(bouncerCovered("com.android.keyguard.KeyguardSimInputView", keyboard = 2, hardKeyboardHidden = 1))
    }

    // The device's PIN and password, and AOSP's two SIM views (LineageOS 24 has no
    // MediaTek view); never their common parent, MediaTek's views.
    @Test fun onlyTheDevicesCodesAndTheSimViewsAreCovered() {
        assertTrue(bouncerDressed("com.android.keyguard.KeyguardPINView"))
        assertTrue(bouncerDressed("com.android.keyguard.KeyguardPasswordView"))
        assertTrue(bouncerDressed("com.android.keyguard.KeyguardSimPinView"))
        assertTrue(bouncerDressed("com.android.keyguard.KeyguardSimPukView"))
        assertEquals("com.android.keyguard.KeyguardSimPinView", KEYGUARD_SIM_PIN_VIEW)
        assertEquals("com.android.keyguard.KeyguardSimPukView", KEYGUARD_SIM_PUK_VIEW)
        assertFalse(bouncerDressed("com.mediatek.keyguard.Telephony.KeyguardSimPinPukMeView"))
        assertFalse(bouncerDressed("com.android.keyguard.KeyguardSimInputView"))
        // The 9900 had no pattern (bold7-pas-de-schema): Android's own grid is never covered.
        assertFalse(bouncerDressed("com.android.keyguard.KeyguardPatternView"))
        assertFalse(bouncerDressed("com.mediatek.keyguard.AntiTheft.KeyguardAntiTheftLockView"))
        assertEquals("com.android.keyguard.KeyguardPasswordView", KEYGUARD_PASSWORD_VIEW)
    }

    // Q25_keyboard.kcm: W carries 1, C carries 9 (`number:`); the 0 key is KEYCODE_0 itself.
    @Test fun lettersCarryingADigitReachThePinAsThatDigit() {
        assertEquals(android.view.KeyEvent.KEYCODE_1, pinKeyCode(android.view.KeyEvent.KEYCODE_W, '1', repeat = 0))
        assertEquals(android.view.KeyEvent.KEYCODE_9, pinKeyCode(android.view.KeyEvent.KEYCODE_C, '9', repeat = 0))
    }

    // The real `number:` values of Q25_keyboard.kcm for ENTER and SPACE, and a letter carrying '0'.
    @Test fun onlyDigitsBecomeDigitKeys() {
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_ENTER, '\n', repeat = 0))
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_SPACE, ' ', repeat = 0))
        assertEquals(android.view.KeyEvent.KEYCODE_0, pinKeyCode(android.view.KeyEvent.KEYCODE_A, '0', repeat = 0))
    }

    // A letter held down types one digit: its repeats reach Android as letters, which no PIN view
    // takes, so a key pressed in a pocket or held too long never fills the field.
    @Test fun aHeldLetterTypesOneDigit() {
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_W, '1', repeat = 1))
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_W, '1', repeat = 30))
    }

    // onKeyDown is shared with the SIM's views and MediaTek's anti-theft lock: the device's PIN,
    // and MediaTek's SIM view only while OS 7's box covers it (its pad then hidden, the keyboard
    // the only way to type); never the anti-theft lock, AOSP's SIM views, a SIM view uncovered.
    @Test fun keysTranslatedForTheDevicePinAndTheCoveredSimViewOnly() {
        assertTrue(pinKeysTranslated("com.android.keyguard.KeyguardPINView", simCovered = false))
        for (sim in listOf(KEYGUARD_SIM_PIN_VIEW, KEYGUARD_SIM_PUK_VIEW)) {
            assertTrue(pinKeysTranslated(sim, simCovered = true))
            assertFalse(pinKeysTranslated(sim, simCovered = false))
        }
        assertFalse(pinKeysTranslated("com.android.keyguard.KeyguardPasswordView", simCovered = true))
        assertFalse(pinKeysTranslated("com.mediatek.keyguard.Telephony.KeyguardSimPinPukMeView", simCovered = true))
        assertFalse(pinKeysTranslated("com.android.keyguard.KeyguardSimInputView", simCovered = true))
        assertFalse(pinKeysTranslated("com.mediatek.keyguard.AntiTheft.KeyguardAntiTheftLockView", simCovered = true))
        assertEquals(KEYGUARD_PIN_VIEW, "com.android.keyguard.KeyguardPINView")
    }

    @Test fun otherKeysReachThePinAsTheyCame() {
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_0, '0', repeat = 0))
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_NUMPAD_5, '5', repeat = 0))
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_A, '\u0000', repeat = 0))
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_DEL, '\u0000', repeat = 0))
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_ENTER, '\u0000', repeat = 0))
        // Enter, the trackpad's click, Back and Escape: Android's, untouched (confirm, back, nothing).
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_DPAD_CENTER, '\u0000', repeat = 0))
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_BACK, '\u0000', repeat = 0))
        assertEquals(null, pinKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE, '\u0000', repeat = 0))
    }

    // --- SIM's PIN and PUK -------------------------------------------------------

    // AOSP's SIM PIN view asks for the PIN, always. The PUK view's state is its controller's
    // (KeyguardSimPukViewController.StateMachine), which the view does not hold: it is read from
    // the prompt Android shows for it (kg_puk_enter_puk_hint, kg_puk_enter_pin_hint,
    // kg_enter_confirm_pin_hint), the last one kept under Android's other words (a wrong code, the
    // attempts left), the PUK at first, as the state machine starts.
    private val puk = "SIM is now disabled. Enter PUK code to continue. Contact carrier for details."
    private val pukPrompts = mapOf(puk to SimEntry.PUK, "Enter desired PIN code" to SimEntry.NEW_PIN, "Confirm desired PIN code" to SimEntry.CONFIRM_PIN)

    @Test fun simEntryFollowsTheViewAndAndroidsPrompt() {
        assertEquals(SimEntry.PIN, simEntry(KEYGUARD_SIM_PIN_VIEW, "Confirm desired PIN code", pukPrompts, last = null))
        assertEquals(SimEntry.PUK, simEntry(KEYGUARD_SIM_PUK_VIEW, null, pukPrompts, last = null))
        assertEquals(SimEntry.NEW_PIN, simEntry(KEYGUARD_SIM_PUK_VIEW, " Enter desired PIN code ", pukPrompts, last = SimEntry.PUK))
        assertEquals(SimEntry.CONFIRM_PIN, simEntry(KEYGUARD_SIM_PUK_VIEW, "Confirm desired PIN code", pukPrompts, last = SimEntry.NEW_PIN))
        assertEquals(SimEntry.NEW_PIN, simEntry(KEYGUARD_SIM_PUK_VIEW, "PIN codes does not match", pukPrompts, last = SimEntry.NEW_PIN))
        assertEquals(null, simEntry("com.android.keyguard.KeyguardPINView", puk, pukPrompts, last = null))
    }

    // LineageOS 24's strings (SystemUI's values/strings.xml): the prompt of each state is OS 7's
    // label, never an error; a wrong code with the attempts left is Android's, in OS 7's dialog.
    private val simPrompts = listOf("Enter SIM PIN.", puk, "Enter desired PIN code", "Confirm desired PIN code", "Enter your PIN")

    @Test fun simMessagesReadWithoutAndroidsPrompts() {
        assertEquals(null, bouncerText(listOf("Enter SIM PIN."), simPrompts))
        assertEquals(null, bouncerText(listOf("SIM is now disabled. Enter PUK code to continue. Contact carrier for details."), simPrompts))
        assertEquals(null, bouncerText(listOf("Confirm desired PIN code"), simPrompts))
        val wrong = "Incorrect SIM PIN code, you have 2 remaining attempts."
        assertEquals(wrong, bouncerText(listOf(" $wrong "), simPrompts))
        assertEquals(BouncerBox.ERROR, bouncerBox(bouncerText(listOf(wrong), simPrompts), typed = 0))
        assertEquals("Type a PIN that is 4 to 8 numbers.", bouncerText(listOf("Type a PIN that is 4 to 8 numbers."), simPrompts))
        // Android's count stays in Android's words: nothing maps it to OS 7's wrong-password text.
        assertEquals(wrong, errorText(wrong, emptyList(), "Incorrect password!"))
    }

    // --- Place Call -------------------------------------------------------

    // KeyEvent.ACTION_DOWN 0, ACTION_UP 1; KEYCODE_CALL 5, KEYCODE_ENTER 66.
    @Test fun placeCallOnTheReleaseOfAPressTheLockScreenSawWhole() {
        val key = CallKeyPress()
        assertFalse(key.released(action = 0, keyCode = 5, repeat = 0, downTime = 10, canceled = false))
        assertFalse(key.released(action = 0, keyCode = 5, repeat = 1, downTime = 10, canceled = false))
        assertTrue(key.released(action = 1, keyCode = 5, repeat = 0, downTime = 10, canceled = false))
        // A second press opens it again; any other key never.
        assertFalse(key.released(action = 0, keyCode = 5, repeat = 0, downTime = 20, canceled = false))
        assertTrue(key.released(action = 1, keyCode = 5, repeat = 0, downTime = 20, canceled = false))
        assertFalse(key.released(action = 0, keyCode = 66, repeat = 0, downTime = 30, canceled = false))
        assertFalse(key.released(action = 1, keyCode = 66, repeat = 0, downTime = 30, canceled = false))
    }

    // PhoneWindowManager answers a ringing call on the press and keeps it from SystemUI, never the
    // release: a release without its press is that answer's, nothing opens.
    @Test fun aReleaseWithoutItsPressOpensNothing() {
        val key = CallKeyPress()
        assertFalse(key.released(action = 1, keyCode = 5, repeat = 0, downTime = 10, canceled = false))
        key.released(action = 0, keyCode = 5, repeat = 0, downTime = 10, canceled = false)
        assertFalse(key.released(action = 1, keyCode = 5, repeat = 0, downTime = 11, canceled = false))
        // Its press already spent: the same release again opens nothing.
        key.released(action = 0, keyCode = 5, repeat = 0, downTime = 12, canceled = false)
        assertTrue(key.released(action = 1, keyCode = 5, repeat = 0, downTime = 12, canceled = false))
        assertFalse(key.released(action = 1, keyCode = 5, repeat = 0, downTime = 12, canceled = false))
    }

    @Test fun aCanceledPressOpensNothing() {
        val key = CallKeyPress()
        key.released(action = 0, keyCode = 5, repeat = 0, downTime = 10, canceled = false)
        assertFalse(key.released(action = 1, keyCode = 5, repeat = 0, downTime = 10, canceled = true))
    }

    // StatusBarState KEYGUARD 1, SHADE 0, SHADE_LOCKED 2; AudioAttributes.USAGE_MEDIA 1,
    // USAGE_VOICE_COMMUNICATION 2, USAGE_ALARM 4, USAGE_NOTIFICATION_RINGTONE 6.
    @Test fun placeCallOnlyOverTheLockScreenItselfWithNothingRingingNorAnyCall() {
        fun opens(
            state: Int = 1, showing: Boolean = true, occluded: Boolean = false, bouncer: Boolean = false,
            interactive: Boolean = true, inCall: Boolean = false, playing: List<Int> = emptyList(),
        ) = placeCallOpens(state, showing, occluded, bouncer, interactive, inCall, playing)
        assertTrue(opens())
        assertTrue(opens(playing = listOf(1)))
        assertFalse(opens(state = 0))
        assertFalse(opens(state = 2))
        assertFalse(opens(showing = false))
        assertFalse(opens(occluded = true))
        // The device's PIN or password, the SIM's PIN or PUK: the bouncer is up.
        assertFalse(opens(bouncer = true))
        assertFalse(opens(interactive = false))
        assertFalse(opens(inCall = true))
        assertFalse(opens(playing = listOf(1, 4)))
        assertFalse(opens(playing = listOf(6)))
        assertFalse(opens(playing = listOf(2)))
    }

    // A missing image would throw in SystemUI's process, on the lock screen or the bouncer.
    @Test fun lockImagesAreThere() {
        val sysui = LOCK_IMAGES.filterNot { File("../../assets-gen/sysui/res/drawable-nodpi/$it.png").exists() }
        val ui = BOUNCER_UI_IMAGES.filterNot { File("../../assets-gen/ui/res/drawable-nodpi/$it.png").exists() || File("../../assets-gen/ui/res/drawable-nodpi/$it.9.png").exists() }
        assertEquals(emptyList(), sysui + ui)
    }

    // Class names written as strings in Bouncer.kt and PlaceCall.kt, tied to the Q25's SystemUI; LockTargets.kt
    // compiles the members.
    @Test fun bouncerTargetClassesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(KEYGUARD_PIN_VIEW, KEYGUARD_PASSWORD_VIEW, PASSWORD_TEXT_VIEW, KEYGUARD_PIN_BASED_VIEW, KEYGUARD_SECURITY_CONTAINER, KEYGUARD_SIM_PIN_VIEW, KEYGUARD_SIM_PUK_VIEW, KEYGUARD_STATE)) {
            assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
        }
    }
}
