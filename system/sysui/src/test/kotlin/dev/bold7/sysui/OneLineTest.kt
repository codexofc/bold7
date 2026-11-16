package dev.bold7.sysui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Phone's one-line banner and the call indicator (docs/BBOS7-VISUEL.md §18,
// "Une ligne"): frames found by image search in sim-18, sim-109, sim-136 and sim-141, texts by
// rendering the firmware's BBAlpha Sans Condensed at 27 px.
class OneLineTest {
    // sim-141 (the call log, no call), sim-18 (ringing), sim-109 (in a call): the Phone's screens
    // all have the one-line banner; the Calendar its own, without the network's name nor the
    // battery (sim-220); Contacts, MemoPad, Tasks and Pictures their title bar alone (sim-178,
    // sim-254, sim-267, sim-344), Calculator nothing (sim-249); Messages has the two-line one
    // (sim-43), and so does every other app.
    @Test fun eachAppHasThe9900sBar() {
        assertEquals(BarKind.ONE_LINE, barKind("dev.bold7.phone"))
        assertEquals(BarKind.PLAIN_LINE, barKind("dev.bold7.calendar"))
        for (app in listOf("contacts", "memopad", "tasks", "pictures", "calculator")) assertEquals(BarKind.NONE, barKind("dev.bold7.$app"), app)
        assertEquals(BarKind.TWO_LINES, barKind("dev.bold7.messages"))
        assertEquals(BarKind.TWO_LINES, barKind("com.google.android.apps.messaging"))
        assertEquals(BarKind.TWO_LINES, barKind("com.android.systemui"))
        assertEquals(BarKind.TWO_LINES, barKind(null))
        assertTrue(oneLineBanner(BarKind.ONE_LINE))
        assertTrue(oneLineBanner(BarKind.PLAIN_LINE))
        assertFalse(oneLineBanner(BarKind.TWO_LINES))
        assertFalse(oneLineBanner(BarKind.NONE))
        assertEquals(BarBackground.ONE_LINE, barBackground("dev.bold7.phone"))
        // The Calendar draws under its line as the Phone does (UnderBar): nothing black under it.
        assertEquals(BarBackground.ONE_LINE, barBackground("dev.bold7.calendar"))
    }

    // sim-53: Text Messages names itself in the middle; sim-43: the unified Messages, which names
    // nothing, shows the network. Another app's label never shows.
    @Test fun theMiddleIsTheScreensTitleElseTheNetwork() {
        assertEquals("Text Messages", bannerTitle("dev.bold7.messages", "Text Messages", "Free"))
        assertEquals("Free", bannerTitle("dev.bold7.messages", null, "Free"))
        assertEquals("Free", bannerTitle("dev.bold7.messages", "  ", "Free"))
        assertEquals("Free", bannerTitle("com.android.chrome", "Some page", "Free"))
        assertEquals("Free", bannerTitle(null, "Text Messages", "Free"))
        assertNull(bannerTitle("com.android.chrome", "Some page", null))
    }

    // The Phone draws from y 0 (black for the line, content from y 34): the bar's window paints
    // nothing under it, so the 33 px below the line show the Phone, not a band of black.
    @Test fun barWindowPaintsBlackOnlyForOtherApps() {
        assertEquals(0f, barAlpha(barBackground("dev.bold7.phone")))
        assertEquals(0f, barAlpha(barBackground("com.android.systemui")))
        assertEquals(1f, barAlpha(barBackground("dev.bold7.launcher")))
    }

    // CALL_STATE_OFFHOOK (2): the handset and its arrows (sim-109, sim-138 on the speaker, sim-139
    // on hold), the struck microphone while muted (sim-136); nothing while it rings (sim-18,
    // sim-200) nor without a call.
    @Test fun callIndicatorOffTheHookOnly() {
        assertEquals("banner_call", callImage(2, micMuted = false))
        assertEquals("banner_call_muted", callImage(2, micMuted = true))
        assertNull(callImage(1, micMuted = false))
        assertNull(callImage(0, micMuted = true))
    }

    // The demonstration setting (bold7_call_demo): 1 a call, 2 a muted call, anything else none.
    @Test fun callDemoShowsTheIndicatorWithoutACall() {
        assertEquals("banner_call", callDemo(1))
        assertEquals("banner_call_muted", callDemo(2))
        assertNull(callDemo(0))
        assertNull(callDemo(-1))
    }

    // sim-109, 640 px: bars' frame at (589, 8), "edge" (60 px of advance) drawn from 522, Wi-Fi
    // at (485, 5), the call at (459, 2), the battery at (402, 2): 7 px between two.
    @Test fun rowSitsWhereSim109HasIt() {
        val p = OneLine.places(640, coverage = 60, wifi = true, call = true)
        assertEquals(589, p.signalX)
        assertEquals(522, p.coverageX)
        assertEquals(485, p.wifiX)
        assertEquals(459, p.callX)
        assertEquals(402, p.batteryX)
    }

    // sim-18 and sim-141: no call, the battery at (428, 2). On the Q25 the row keeps its distance
    // to the right edge.
    @Test fun withoutACallTheBatteryMovesRight() {
        assertEquals(428, OneLine.places(640, 60, wifi = true, call = false).batteryX)
        assertEquals(669, OneLine.places(720, 60, wifi = true, call = false).signalX)
        // Nothing missing leaves a hole: no coverage, no Wi-Fi, the battery next to the bars.
        assertEquals(589 - 7 - 50, OneLine.places(640, null, wifi = false, call = false).batteryX)
    }

    // A counter in the one-line row (D): between the battery and the call, its number then its icon.
    @Test fun countersBetweenTheBatteryAndTheCall() {
        val p = OneLine.places(640, 60, wifi = true, call = true, numbers = listOf(15), icons = listOf(33))
        assertEquals(listOf(459 - 7 - 33 - 4 - 15 to 459 - 7 - 33), p.counters)
        assertEquals(459 - 7 - 33 - 4 - 15 - 7 - 50, p.batteryX)
    }

    // "Simulated Network 05:04": the name's ink from x 7 (origin 5, "S" bears 2), the time's from
    // 246, 4 px after the name's advance (235 at 27 px); both on baseline 26 (ink to y 25), the
    // coverage on 27 (ink to y 32 with the "g"). The bar ends on y 33.
    @Test fun nameThenTimeOnTheLine() {
        assertEquals(244, OneLine.timeX(235))
        assertEquals(OneLine.NAME_X, OneLine.timeX(null))
        assertEquals(26, OneLine.BASELINE)
        assertEquals(27, OneLine.COVERAGE_BASELINE)
        assertEquals(34, OneLine.HEIGHT)
    }

    // Android's status icons left of the battery (D), a 24 px image at y 5 like the Wi-Fi.
    @Test fun statusIconsBeforeTheBattery() {
        assertEquals(402 - 7, OneLine.iconsRight(402))
        assertEquals(OneLine.WIFI_Y, (OneLine.HEIGHT - 24) / 2)
    }

    // In the two-line banner the call goes with the counters, banner.pme's area 2 (D): an icon
    // without a number, after them, all centred together.
    @Test fun callAmongTheTwoLineCounters() =
        assertEquals(listOf(277 to 296, 344 to 344), Banner.counterXs(640, listOf(15, null), listOf(33, 19)))

    @Test fun callImagesAreThere() = assertEquals(
        emptyList(),
        listOf("banner_call", "banner_call_muted").filterNot { File("../../assets-gen/sysui/res/drawable-nodpi/$it.png").exists() },
    )

    // sim-337 (locked, the alarm snoozed) and sim-338 (the home): the alarm indicator's frame at
    // (63, 4), right of the battery's (3, 2), found by image search; the same image as an alarm set.
    @Test fun lockAlarmRightOfTheBattery() {
        assertEquals(63, dev.bold7.design.HomeBanner.ALARM_X)
        assertEquals(4, dev.bold7.design.HomeBanner.ALARM_Y)
        assertTrue(File("../../assets-gen/sysui/res/drawable-nodpi/banner_alarm.png").exists())
    }
}
