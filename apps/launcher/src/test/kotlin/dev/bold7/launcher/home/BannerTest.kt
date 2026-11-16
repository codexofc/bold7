package dev.bold7.launcher.home

import dev.bold7.hub.data.ItemKind
import dev.bold7.launcher.profiles.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BannerTest {
    @Test fun theBatteryShowsTenthsRoundedUpRedBelowTenAndThePlugWhileCharging() {
        assertEquals(Frame(0), batteryFrame(0, charging = false))
        assertEquals(Frame(1), batteryFrame(5, charging = false))
        assertEquals(Frame(1), batteryFrame(10, charging = false))
        assertEquals(Frame(2), batteryFrame(11, charging = false))
        assertEquals(Frame(10), batteryFrame(100, charging = false))
        assertEquals(Frame(6, row = 2), batteryFrame(55, charging = true))
        // Unknown: the strip's "?".
        assertEquals(Frame(11), batteryFrame(-1, charging = false))
    }

    @Test fun theSignalStripGoesOffNoServiceThenNoBarToFive() {
        assertEquals(Frame(0), signalFrame(level = 3, inService = false, radioOff = true))
        assertEquals(Frame(1), signalFrame(level = 3, inService = false, radioOff = false))
        // Android's 0 to 4 on the theme's 0 to 5 bars: a signal is never shown as no bar.
        assertEquals(listOf(2, 4, 5, 6, 7), (0..4).map { signalFrame(it, inService = true, radioOff = false).column })
    }

    @Test fun wifiShowsOnlyWhenConnected() {
        assertNull(wifiFrame(null))
        assertEquals(Frame(1), wifiFrame(0))
        assertEquals(Frame(5), wifiFrame(4))
    }

    @Test fun theProfilePicksTheSpeakersFrame() {
        assertEquals(Frame(0), profileFrame(Profile.NORMAL))
        assertEquals(Frame(1), profileFrame(Profile.VIBRATE))
        assertEquals(Frame(2), profileFrame(Profile.SILENT))
        assertEquals(Frame(7), profileFrame(Profile.MEDIUM))
    }

    @Test fun coverageNamesTheNetworkGenerationAsTheBannerDid() {
        assertEquals("edge", coverage(NetworkType.EDGE))
        assertEquals("3G", coverage(NetworkType.HSPA))
        assertEquals("4G", coverage(NetworkType.LTE))
        assertEquals("", coverage(NetworkType.UNKNOWN))
    }

    @Test fun indicatorsCountEachKindInTheBannersOrderWithTheThemesIcons() {
        val counts = mapOf(ItemKind.MISSED_CALL to 2, ItemKind.EMAIL to 3, ItemKind.NOTIFICATION to 1, ItemKind.SMS to 4)
        assertEquals(
            listOf(Indicator(UNREAD_EMAIL, 3), Indicator(UNREAD_SMS, 4), Indicator(UNREAD_MISSED_CALL, 2)),
            indicators(counts),
        )
        assertEquals(emptyList(), indicators(mapOf(ItemKind.SMS to 0)))
    }

    // A pull down on the banner opens the list of notifications; a tap, a sideways or upward move does not.
    @Test fun aPullDownIsMostlyDownwardsAndPastTwiceTheSlop() {
        assertTrue(bannerPulled(dx = 10f, dy = 50f, slop = 16f))
        assertFalse(bannerPulled(dx = 0f, dy = 30f, slop = 16f))
        assertFalse(bannerPulled(dx = 60f, dy = 50f, slop = 16f))
        assertFalse(bannerPulled(dx = 0f, dy = -80f, slop = 16f))
    }
}
