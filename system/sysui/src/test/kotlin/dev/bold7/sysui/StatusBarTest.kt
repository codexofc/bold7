package dev.bold7.sysui

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StatusBarTest {
    // The 9900's counters (sim-43: "2" and the missed call; gsm 022: "1" mail, "1" text message),
    // the Hub's unread by kind: one per kind, in the home banner's order (launcher,
    // Banner.indicators): mail, text messages, missed calls, invitations. A kind with nothing
    // unread, or unknown, shows nothing: another app's notifications have no counter on OS 7.
    @Test fun countersAreTheHubsInTheHomesOrder() {
        assertEquals(
            listOf(
                Counter("banner_count_email", 3),
                Counter("banner_count_sms", 1),
                Counter("banner_count_missed_call", 2),
                Counter("banner_count_calendar", 1),
            ),
            kindCounters(mapOf("NOTIFICATION" to 2, "INVITATION" to 1, "MISSED_CALL" to 2, "SMS" to 1, "EMAIL" to 3)),
        )
        assertEquals(listOf(Counter("banner_count_sms", 2)), kindCounters(mapOf("SMS" to 2, "EMAIL" to 0, "FAX" to 4)))
        assertEquals(emptyList(), kindCounters(emptyMap()))
    }

    // banner.pme, on the 720 px of the Q25 (docs/BBOS7-VISUEL.md §18): what is anchored to the
    // right keeps its distance to the edge.
    @Test fun rightAnchorsKeepTheirDistanceToTheEdge() {
        assertEquals(586, Banner.signalX(640)) // sim-43, gsm 022: the strip's frame found at x 586
        assertEquals(666, Banner.signalX(720))
        assertEquals(592, Banner.wifiX(640)) // sim-43: the Wi-Fi's frame at (592, 35)
        assertEquals(672, Banner.wifiX(720))
    }

    // sim-43: "edge" drawn from x 520 (its ink from 521), 60 px of advance, the bars' frame at 586.
    @Test fun coverageEndsBeforeTheBars() = assertEquals(520, Banner.coverageX(640, 60))

    // The second line's images and texts where sim-43 and gsm 022 have them (frames found by
    // image search, texts by searching the firmware's TTF rendered at 27 px): the time's
    // baseline at y 56, a counter's number at 58, the Wi-Fi's frame at y 35.
    @Test fun secondLineBaselinesAndWifi() {
        assertEquals(56, Banner.TIME_BASELINE)
        assertEquals(58, Banner.COUNT_BASELINE)
        assertEquals(35, Banner.WIFI_Y)
    }

    // Android's status icons (GPS, Bluetooth, NFC...) in a box whose middle is the Wi-Fi's, so
    // that a 24 px image (the GPS) lies at y 35 like the Wi-Fi, as ImageView's CENTER places it.
    @Test fun statusIconsShareTheWifisMiddle() {
        assertEquals(Banner.WIFI_Y + 12, Banner.ICONS_Y + Banner.ICONS_HEIGHT / 2)
        assertEquals(Banner.WIFI_Y, Banner.ICONS_Y + Math.round((Banner.ICONS_HEIGHT - 24) * 0.5f))
        assertTrue(Banner.ICONS_Y + Banner.ICONS_HEIGHT <= Banner.HEIGHT)
    }

    // Each counter as (number's x, icon's x), centred together: sim-43, "2" from x 294 (15 px of
    // advance at 27 px), the missed call's frame at 313; gsm 022, "1" at 260 and 327, their icons
    // at 279 and 346. On the Q25, centred on x 360.
    @Test fun countersSitWhereThe9900HasThem() {
        assertEquals(listOf(294 to 313), Banner.counterXs(640, listOf(15), listOf(33)))
        assertEquals(listOf(260 to 279, 327 to 346), Banner.counterXs(640, listOf(15, 15), listOf(33, 33)))
        assertEquals(listOf(334 to 353), Banner.counterXs(720, listOf(15), listOf(33)))
    }

    // The time and the counters' numbers are BBAlpha Sans Condensed 27 px on the 9900: "00:30"
    // 66 px wide on sim-43, as the TTF renders it at 27 (79 at 32, the theme's font-size).
    @Test fun timeAndNumbersAre27Px() = assertEquals(27, dev.bold7.design.Typography.AppBarTime.sizePx)

    /** A dimension of the framework overlay, in px; null if the overlay does not set it. */
    private fun overlayDimen(name: String): Int? {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("../overlays/android/src/main/res/values/dimens.xml"))
        val dimens = xml.getElementsByTagName("dimen")
        return (0 until dimens.length).map { dimens.item(it) }
            .singleOrNull { it.attributes.getNamedItem("name").nodeValue == name }
            ?.textContent?.trim()?.removeSuffix("px")?.toInt()
    }

    // The window SystemUI gives the bar, and every app's top inset, is the banner's 67 px
    // (status_bar_height_default, SystemBarUtils); the older status_bar_height that some apps
    // read (BenOS: portrait 40 dp, landscape 28 dp) says the same.
    @Test fun overlayGivesTheBarTheBannersHeight() = assertEquals(
        listOf(Banner.HEIGHT, Banner.HEIGHT, Banner.HEIGHT),
        listOf("status_bar_height_default", "status_bar_height_portrait", "status_bar_height_landscape").map(::overlayDimen),
    )

    @Test fun nothingUnderTheBarOverTheKeyguardBlackElsewhere() {
        assertEquals(BarBackground.NONE, barBackground("com.android.systemui"))
        assertEquals(BarBackground.BLACK, barBackground("dev.bold7.launcher"))
        assertEquals(BarBackground.BLACK, barBackground(null))
    }

    // Class names written as strings in StatusBarLayout.kt, tied to the Q25's SystemUI like
    // GuardTest does for the others; StatusBarTargets.kt compiles the members.
    @Test fun statusBarTargetClassesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(CLOCK_VIEW, COMMAND_QUEUE, BAR_BACKGROUND, HEADS_UP_APPEARANCE, ICON_VIEW)) {
            assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
        }
    }

    // Every image the counters can name, so that none is missing at run time.
    @Test fun everyCounterImageIsThere() {
        val names = KIND_IMAGES.values.toSet()
        assertEquals(4, names.size)
        for (name in names) assertTrue(File("../../assets-gen/sysui/res/drawable-nodpi/$name.png").exists(), name)
    }
}
