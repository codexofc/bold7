package dev.bold7.sysui

import android.app.Notification
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeadsUpTest {
    private val callStyle = "android.app.Notification\$CallStyle"

    // OS 7 shows nothing over the screen when a message arrives: sound, LED, counters, list.
    @Test fun anOrdinaryCardIsCut() {
        assertFalse(cardKept(fullScreenIntent = false, flags = 0, template = null, category = null))
        assertFalse(cardKept(false, 0, "android.app.Notification\$MessagingStyle", Notification.CATEGORY_MESSAGE))
        assertFalse(headsUp(android = true, dozing = false, kept = false) { false })
    }

    // A call or an alarm must never go unnoticed: a full screen intent, one the app asked for but
    // was denied (Android then keeps its card up, sticky), a CallStyle, a call or an alarm.
    @Test fun callsAlarmsAndFullScreenIntentsKeepTheirCard() {
        assertTrue(cardKept(fullScreenIntent = true, flags = 0, template = null, category = null))
        assertTrue(cardKept(false, FLAG_FSI_DENIED or Notification.FLAG_ONGOING_EVENT, null, null))
        assertTrue(cardKept(false, 0, callStyle, null))
        assertTrue(cardKept(false, 0, null, Notification.CATEGORY_CALL))
        assertTrue(cardKept(false, 0, null, Notification.CATEGORY_ALARM))
        assertTrue(headsUp(android = true, dozing = false, kept = true) { error("not asked") })
    }

    // The emergency switch (bold7_android_shade=1) gives every card back.
    @Test fun theSwitchGivesTheCardsBack() {
        assertTrue(headsUp(android = true, dozing = false, kept = false) { true })
    }

    // Dozing, Android's answer is a pulse of the ambient display, not a card: left to the lock screen.
    @Test fun aPulseStaysAndroids() {
        assertTrue(headsUp(android = true, dozing = true, kept = false) { error("not asked") })
    }

    // Never a card Android would not show (do not disturb, low importance, screen off...).
    @Test fun neverMoreThanAndroid() {
        for (dozing in listOf(false, true)) for (kept in listOf(false, true)) for (shade in listOf(false, true)) {
            assertFalse(headsUp(android = false, dozing = dozing, kept = kept) { shade })
        }
    }

    @Test fun templateIsTheFrameworksCallStyle() {
        assertEquals(Notification.CallStyle::class.java.name, callStyle)
    }

    // Hidden from the SDK, inlined from the Q25's framework.jar when this compiles.
    @Test fun deniedFlagIsTheFrameworks() {
        assertEquals(Notification.FLAG_FSI_REQUESTED_BUT_DENIED, FLAG_FSI_DENIED)
    }

    // The members are checked by the compiler, in HeadsUpTargets.kt.
    @Test fun targetClassesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(INTERRUPT_STATE_PROVIDER, NOTIFICATION_ENTRY)) {
            assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
        }
    }
}
