package dev.bold7.sysui

import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShadeTest {
    // StatusBarState.SHADE, KEYGUARD, SHADE_LOCKED.
    private val shade = 0
    private val keyguard = 1
    private val shadeLocked = 2

    // BBOS 7 had no notification shade: unlocked, Android's shade is its quick settings alone
    // (the toggles), the notifications in the home's list.
    @Test fun anUnlockedOpeningLeavesTheNotificationsOut() {
        assertTrue(shadeAlone(shade, locked = false, cardPinned = false, androidShade = false))
    }

    // The emergency switch (bold7_android_shade=1) gives Android's shade back, notifications under the panel.
    @Test fun theSwitchGivesAndroidsShadeBack() {
        assertFalse(shadeAlone(shade, locked = false, cardPinned = false, androidShade = true))
    }

    // A swipe down on a pinned card (incoming call, alarm) unpins it for good: hidden with the list,
    // it would not come back once the panel closed. With a card pinned, Android's shade.
    @Test fun aPinnedCardKeepsAndroidsShade() {
        assertFalse(shadeAlone(shade, locked = false, cardPinned = true, androidShade = false))
    }

    // The lock screen keeps its own: the keyguard, its pulled-down shade, an app shown over it.
    @Test fun lockedKeepsAndroidsShade() {
        assertFalse(shadeAlone(keyguard, locked = true, cardPinned = false, androidShade = false))
        assertFalse(shadeAlone(shadeLocked, locked = true, cardPinned = false, androidShade = false))
        assertFalse(shadeAlone(shade, locked = true, cardPinned = false, androidShade = false))
    }

    // A heads-up card pinned over the closed shade (incoming call, alarm, full screen intent kept
    // by HeadsUp.kt) makes Android's isExpanded() true and the panel STATE_OPENING: nothing is
    // pulled down, so no decision, and the stack that draws the card stays visible.
    @Test fun aPinnedCardAloneIsNoOpening() {
        assertFalse(shadeOpening(fraction = 0f, tracking = false, instantExpanding = false, heightAnimating = false))
        val none = ShadeStep(decided = false, alone = false)
        val step = shadeStep(none, opening = false) { error("not asked") }
        assertEquals(none, step)
        assertEquals(emptyList(), shadeEffects(none, step))
    }

    // A real opening: a finger from onTrackingStarted on (before the first pixel, so before
    // updatePanelExpanded clears the effects), expand() at once, an animation, or pulled down.
    @Test fun aRealOpening() {
        assertTrue(shadeOpening(0f, tracking = true, instantExpanding = false, heightAnimating = false))
        assertTrue(shadeOpening(0f, tracking = false, instantExpanding = true, heightAnimating = false))
        assertTrue(shadeOpening(0f, tracking = false, instantExpanding = false, heightAnimating = true))
        assertTrue(shadeOpening(0.01f, tracking = false, instantExpanding = false, heightAnimating = false))
    }

    // Decided once, when an opening really starts (a swipe down on the card included); kept while
    // it lasts; dropped once the shade is closed, even with the card still pinned.
    @Test fun decidedOncePerOpening() {
        val none = ShadeStep(decided = false, alone = false)
        val alone = shadeStep(none, opening = true) { true }
        assertEquals(ShadeStep(decided = true, alone = true), alone)
        assertEquals(alone, shadeStep(alone, opening = true) { error("not asked twice") })
        assertEquals(ShadeStep(decided = true, alone = false), shadeStep(none, opening = true) { false })
        assertEquals(none, shadeStep(alone, opening = false) { error("not asked") })
    }

    // QS expanded with the panel before the stack is hidden; at the close, the stack's visibility
    // always goes back to Android, whatever the opening was.
    @Test fun effectsInOrder() {
        val none = ShadeStep(decided = false, alone = false)
        val alone = ShadeStep(decided = true, alone = true)
        val android = ShadeStep(decided = true, alone = false)
        assertEquals(listOf(ShadeEffect.EXPAND_QS, ShadeEffect.UPDATE_VISIBILITY), shadeEffects(none, alone))
        assertEquals(listOf(ShadeEffect.UPDATE_VISIBILITY), shadeEffects(alone, none))
        assertEquals(listOf(ShadeEffect.UPDATE_VISIBILITY), shadeEffects(android, none))
        assertEquals(emptyList(), shadeEffects(none, android))
        assertEquals(emptyList(), shadeEffects(alone, alone))
        assertEquals(emptyList(), shadeEffects(none, none))
    }

    // The panel stays expanded from the shade to the keyguard (screen off), and the keyguard shows
    // before the bar state changes: the decision holds only while SHADE and no keyguard shows.
    @Test fun onlyWhileUnlockedInTheShade() {
        assertTrue(shadeAloneNow(alone = true, barState = shade, locked = false))
        assertFalse(shadeAloneNow(alone = true, barState = shade, locked = true))
        assertFalse(shadeAloneNow(alone = true, barState = keyguard, locked = false))
        assertFalse(shadeAloneNow(alone = true, barState = shadeLocked, locked = true))
        assertFalse(shadeAloneNow(alone = false, barState = shade, locked = false))
    }

    // As in Android's split shade: QS never folds into its quick row on its own, the whole panel
    // moves. Android's answer otherwise, and whenever QS is not expanded (it can still be pulled down).
    @Test fun quickSettingsNoLongerFoldOnTheirOwn() {
        assertFalse(qsIntercepts(android = true, aloneNow = true, qsExpanded = true, lockedShut = false))
        assertTrue(qsIntercepts(android = true, aloneNow = true, qsExpanded = false, lockedShut = false))
        assertTrue(qsIntercepts(android = true, aloneNow = false, qsExpanded = true, lockedShut = false))
        assertFalse(qsIntercepts(android = false, aloneNow = false, qsExpanded = true, lockedShut = false))
    }

    // The shade shut. OS 7 opens nothing from the top of the screen but on the home ("Highlight
    // and click the areas at the top of the home screen", guide 7.1 p. 33 and 44): in an app, under the
    // lock screen, a pull from the status bar, a request of an app, nothing. The switch gives
    // Android's shade back.
    @Test fun nothingOpensTheShadeAsOnThe9900() {
        assertTrue(shadeShut(androidShade = false))
        assertFalse(shadeShut(androidShade = true))
    }

    // The status bar eats the whole gesture, decided at ACTION_DOWN, never handing it to the shade;
    // with the switch on at ACTION_DOWN, Android's for the whole gesture.
    @Test fun theStatusBarEatsTheGesture() {
        assertEquals(true, statusBarTouchAnswer(shutAtDown = true))
        assertEquals(null, statusBarTouchAnswer(shutAtDown = false))
    }

    // On the lock screen, a pull on its top no longer brings Android's quick settings, whatever
    // Android answers; unlocked, the rule above holds.
    @Test fun theLockScreenPullsNoQuickSettings() {
        assertFalse(qsIntercepts(android = true, aloneNow = false, qsExpanded = false, lockedShut = true))
        assertFalse(qsIntercepts(android = true, aloneNow = false, qsExpanded = true, lockedShut = true))
    }

    // Nor its drag down to the locked shade (DragDownHelper), anywhere on the lock screen; the
    // switch gives Android's answer back.
    @Test fun theLockScreenDragsNothingDown() {
        assertFalse(dragDownEnabled(android = true, androidShade = false))
        assertTrue(dragDownEnabled(android = true, androidShade = true))
        assertFalse(dragDownEnabled(android = false, androidShade = true))
    }

    // Opening the shade alone keeps the LED, sound and vibration of unseen notifications.
    @Test fun aShadeAloneClearsNoEffect() {
        assertFalse(panelRevealedClears(clearEffects = true, alone = true))
        assertTrue(panelRevealedClears(clearEffects = true, alone = false))
        assertFalse(panelRevealedClears(clearEffects = false, alone = false))
    }

    // The switch's value: 1 gives Android's shade back, anything else (absent, 0) keeps BBOS 7's.
    @Test fun onlyOneTurnsTheSwitchOn() {
        assertTrue(androidShadeAsked(1))
        assertFalse(androidShadeAsked(0))
        assertFalse(androidShadeAsked(2))
        assertEquals("bold7_android_shade", ANDROID_SHADE_SETTING)
    }

    // On the home, the launcher draws the banner, and an app without a bar on the 9900 draws its
    // own title: Android's bar never shows there, not even for a swipe from the top (transient
    // bars), a dialog or a heads-up card; any other window keeps it.
    @Test fun theHomeAndTheAppsWithoutABarHideIt() {
        assertTrue(barHidden("dev.bold7.launcher", androidShade = false))
        assertTrue(barHidden("dev.bold7.contacts", androidShade = false))
        assertTrue(barHidden("dev.bold7.calculator", androidShade = false))
        assertFalse(barHidden("dev.bold7.phone", androidShade = false))
        assertFalse(barHidden("com.android.settings", androidShade = false))
        assertFalse(barHidden("com.android.systemui", androidShade = false))
        assertFalse(barHidden(null, androidShade = false))
    }

    // The emergency switch gives Android's bar back, and the shade behind it.
    @Test fun theSwitchShowsTheBar() {
        assertFalse(barHidden("dev.bold7.launcher", androidShade = true))
        assertFalse(barHidden("dev.bold7.tasks", androidShade = true))
    }

    // Only a swipe that shows the status bar is hidden again; the navigation bar's (none on the Q25) is Android's.
    @Test fun onlyTheStatusBarsTransientIsUndone() {
        val statusBars = 1
        val navigationBars = 2
        assertTrue(transientUndone(hidden = true, displayId = 0, types = statusBars or navigationBars))
        assertFalse(transientUndone(hidden = true, displayId = 0, types = navigationBars))
        assertFalse(transientUndone(hidden = false, displayId = 0, types = statusBars))
        assertFalse(transientUndone(hidden = true, displayId = 1, types = statusBars))
    }

    // Shade names its targets by strings; this ties each class to the Q25's SystemUI
    // (stubs/systemui.jar). The compiler checks the members, in ShadeTargets.kt.
    @Test fun shadeTargetClassesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(PANEL_VIEW_CONTROLLER, NOTIFICATION_STACK, QS_CONTROLLER, KEYGUARD_STATE, SURFACES_CALLBACKS, STATUS_BAR_TOUCH, LOCKSCREEN_SHADE)) {
            assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
        }
    }

    // The status bar service's binder proxy, a framework class (stubs/framework.jar, outside the
    // SystemUI fingerprint): package private, so checked here by name; its methods through the
    // interface in ShadeTargets.kt.
    @Test fun statusBarProxyIsInTheQ25Framework() {
        ZipFile("../../stubs/framework.jar").use { jar -> assertNotNull(jar.getEntry(STATUS_BAR_SERVICE_PROXY.replace('.', '/') + ".class")) }
    }

    @Test fun thePowerMenuLeavesTheBarAsItWas() {
        assertTrue(barKept("com.android.systemui", powerMenu = true))
        assertFalse(barKept("com.android.systemui", powerMenu = false)) // the keyguard, the shade
        assertFalse(barKept("dev.bold7.launcher", powerMenu = true)) // back on the home: its own look
        assertFalse(barKept(null, powerMenu = true))
    }
}
