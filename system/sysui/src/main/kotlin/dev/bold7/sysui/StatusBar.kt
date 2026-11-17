package dev.bold7.sysui

import dev.bold7.showcase.Showcase
import dev.bold7.showcase.ShowcaseEvent
import dev.bold7.showcase.pick

// The 9900's banner over an app (banner.pme, "banner" and "twolinebanner" of theme.xml), pure
// Pure. Sources in docs/BBOS7-VISUEL.md §18: parameters of banner.pme (A),
// places measured on sim-43 (C), the square's anchors (D).

/**
 * Places in the banner, in px from its top left corner. Two lines of 33 and 34 px; on the first,
 * the battery, the network's name in the middle and the coverage then the bars at the right edge;
 * on the second, the time, the counters in the middle, the status indicators then Wi-Fi at the
 * right. What the 9900 centres on x 320 is centred on the Q25's middle, what it anchors to an
 * edge keeps its distance to that edge (D).
 */
object Banner {
    /** y 0 to 66 on sim-43, the blue of the list from y 67; also status_bar_height_default of the framework overlay. */
    const val HEIGHT = 67

    /** Top of the second line: the counters' icons at y 35 (sim-43), centred in the 34 px below it. */
    const val LINE = 33

    /** The battery's frame (50x25, its outline from x 4, y 6). */
    const val BATTERY_X = 3
    const val BATTERY_Y = 2

    /** Baseline of the first line: network's name and coverage ("N" and "d" end on y 23, sim-43). */
    const val TOP_BASELINE = 24

    /**
     * Baselines of the second line, found by searching the firmware's TTF in sim-43 and gsm 022:
     * the time ("00:30", "14:32") at y 56, a counter's number ("2", "1") at y 58.
     */
    const val TIME_BASELINE = 56
    const val COUNT_BASELINE = 58

    /** The time's origin ("00:30" and "14:32" drawn from x 4). */
    const val TIME_X = 4

    /** The network's name: layout width=342 around its anchor, the roaming triangle in it (banner.pme). */
    const val NAME_WIDTH = 342

    /** The bars' frame (49x19), found at (586, 5) on sim-43 and gsm 022: 5 px from the edge. */
    private const val SIGNAL_RIGHT = 5
    const val SIGNAL_WIDTH = 49
    const val SIGNAL_Y = 5

    /** From the coverage's advance to the bars' frame: "edge" from x 520, 60 px, the frame at 586 (sim-43). */
    private const val COVERAGE_GAP = 6

    /** The Wi-Fi's frame (30x24), found at (592, 35) on sim-43. */
    private const val WIFI_RIGHT = 18
    const val WIFI_WIDTH = 30
    const val WIFI_Y = 35

    /** Between two status indicators: hpadding=10 of GridIndicators area 1 (banner.pme). */
    const val SPACING = 10

    /**
     * Between two counters: on gsm 022 the second "1" starts 15 px after the first icon's frame
     * (banner.pme gives area 2, the counters', no hpadding).
     */
    private const val COUNT_SPACING = 15

    /** From a counter's number (its advance) to its icon's frame: "2" from x 294, 15 px, the frame at 313 (sim-43). */
    const val COUNT_GAP = 4

    /** A counter's icon: net_rim_Ribbon_UnreadCount-icons33x29, its frame at y 35 (sim-43). */
    const val COUNT_ICON = 29

    /** Right edge of Android's status icons, from the bar's: one spacing before the Wi-Fi (D). */
    const val ICONS_RIGHT = WIFI_RIGHT + WIFI_WIDTH + SPACING

    /**
     * Android's status icons: a box of the second line's height around the Wi-Fi's middle (y 47),
     * each image centred in it (ImageView CENTER), so a 24 px one lies at y 35 like the Wi-Fi (D).
     */
    const val ICONS_HEIGHT = HEIGHT - LINE
    const val ICONS_Y = WIFI_Y + 12 - ICONS_HEIGHT / 2

    fun signalX(width: Int) = width - SIGNAL_RIGHT - SIGNAL_WIDTH
    fun wifiX(width: Int) = width - WIFI_RIGHT - WIFI_WIDTH

    /** The coverage's origin, for a text of [advance] px. */
    fun coverageX(width: Int, advance: Int) = signalX(width) - COVERAGE_GAP - advance

    /**
     * Each counter's (number's x, icon's x), the counters centred together on the bar's middle:
     * [numbers] the numbers' advances (null for an icon alone, the call: its number's x is its
     * icon's), [icons] the icons' widths.
     */
    fun counterXs(width: Int, numbers: List<Int?>, icons: List<Int>): List<Pair<Int, Int>> {
        val total = numbers.indices.sumOf { (numbers[it]?.plus(COUNT_GAP) ?: 0) + icons[it] } + COUNT_SPACING * (numbers.size - 1)
        var x = (width - total) / 2
        return numbers.indices.map { i ->
            val number = x
            numbers[i]?.let { x += it + COUNT_GAP }
            val icon = x
            x += icons[i] + COUNT_SPACING
            number to icon
        }
    }

    /** The call indicator (19x19) with the counters, centred in the second line (D). */
    const val CALL_Y = LINE + (HEIGHT - LINE - 19) / 2
}

/**
 * Places in the one-line banner of the Phone's screens ("banner" of banner.pme, where Messages has
 * "twolinebanner"), in px from its top left corner, measured on sim-109, sim-18 and sim-141 (C):
 * at the left the network's name then the time; at the right, from the edge, the bars, the
 * coverage, the Wi-Fi, the call, the counters (D) and the battery, 7 px apart. What is missing
 * leaves no hole (D). The bar ends on y 33, the call screen's blue from y 34.
 */
object OneLine {
    const val HEIGHT = 34

    /** Name and time: their ink from y 5 (round letters) to 25, BBAlpha Sans Condensed 27. */
    const val BASELINE = 26

    /** "edge": its ink from y 6 to 32, 3 px under the two-line bar's (sim-43), like the bars. */
    const val COVERAGE_BASELINE = 27

    /** "Simulated Network" drawn from x 5 (its ink from 7). */
    const val NAME_X = 5

    /** From the name's advance to the time's origin: "05:04" drawn from 244 (ink 246), the name's advance ends on 240. */
    private const val TIME_GAP = 4

    /** The bars' frame (49x19) at (589, 8): 2 px from the edge. */
    private const val SIGNAL_RIGHT = 2
    const val SIGNAL_Y = 8

    /** The Wi-Fi's frame (30x24) at y 5; the battery's (50x25) and the call's (19x19) at y 2. */
    const val WIFI_Y = 5
    const val FRAME_Y = 2

    /** Between two of the row: battery 402 to 451, call 459 to 477, Wi-Fi 485 to 514, "edge" from 522 to 582, bars from 589. */
    const val SPACING = 7

    private const val BATTERY_WIDTH = 50
    private const val CALL_WIDTH = 19

    /** A counter's number, its icon (29 high) centred in the line (D). */
    const val COUNT_BASELINE = 25
    const val COUNT_ICON_Y = (HEIGHT - Banner.COUNT_ICON) / 2

    class Places(
        val signalX: Int,
        val coverageX: Int,
        val wifiX: Int,
        val callX: Int,
        /** Each counter's (number's x, icon's x). */
        val counters: List<Pair<Int, Int>>,
        val batteryX: Int,
    )

    /**
     * The row from the right edge of a bar [width] px wide: [coverage] the coverage's advance
     * (null without), [wifi] and [call] whether they show, [numbers] and [icons] the counters'.
     * An x of what does not show is meaningless.
     */
    fun places(width: Int, coverage: Int?, wifi: Boolean, call: Boolean, numbers: List<Int> = emptyList(), icons: List<Int> = emptyList()): Places {
        var x = width - SIGNAL_RIGHT - Banner.SIGNAL_WIDTH
        val signalX = x
        coverage?.let { x -= SPACING + it }
        val coverageX = x
        if (wifi) x -= SPACING + Banner.WIFI_WIDTH
        val wifiX = x
        if (call) x -= SPACING + CALL_WIDTH
        val callX = x
        val counters = numbers.indices.reversed().map { i ->
            x -= SPACING + icons[i]
            val icon = x
            x -= Banner.COUNT_GAP + numbers[i]
            x to icon
        }.reversed()
        x -= SPACING + BATTERY_WIDTH
        return Places(signalX, coverageX, wifiX, callX, counters, x)
    }

    /** The time's origin after the network's name of [nameAdvance] px, at the name's place without one. */
    fun timeX(nameAdvance: Int?): Int = if (nameAdvance == null) NAME_X else NAME_X + nameAdvance + TIME_GAP

    /** Android's status icons end one spacing before the battery (D: no capture shows one in this bar). */
    fun iconsRight(batteryX: Int): Int = batteryX - SPACING
}

/** The Phone of Bold7 (apps/phone). */
const val PHONE_PACKAGE = "dev.bold7.phone"

/** Which bar the 9900 shows over an app. */
enum class BarKind {
    /** Messages and every app without a bar of its own (sim-43, sim-53). */
    TWO_LINES,

    /** The Phone's screens, with or without a call (sim-141, sim-18, sim-109). */
    ONE_LINE,

    /** The Calendar's: the one line without the network's name nor the battery (sim-220). */
    PLAIN_LINE,

    /**
     * None: the app's own title bar (Contacts sim-178, MemoPad sim-254, Tasks sim-267, Pictures
     * sim-344) or nothing at all (Calculator sim-249, the Camera's full-screen viewfinder sim-365).
     * The app hides Android's bar; nothing is drawn should it show for a moment (a dialog, a swipe),
     * nor over a camera that draws under it without hiding it (BenOS Camera, edge to edge; Bold7's
     * fork of it hides the bar).
     */
    NONE,
}

/**
 * The bar each of Bold7's apps had on the 9900, by package. Within an app, a screen with no bar
 * (Calendar's new appointment, sim-228; a Messages thread, sim-50) hides Android's bar itself.
 */
fun barKind(controllingPackage: String?): BarKind = when (controllingPackage) {
    PHONE_PACKAGE -> BarKind.ONE_LINE
    "dev.bold7.calendar" -> BarKind.PLAIN_LINE
    "dev.bold7.contacts", "dev.bold7.memopad", "dev.bold7.tasks", "dev.bold7.pictures", "dev.bold7.calculator" -> BarKind.NONE
    "dev.bold7.camera.benos", "com.light.lightcamera", "org.lineageos.aperture" -> BarKind.NONE
    else -> BarKind.TWO_LINES
}

/** Whether the bar is one of the one-line banners ([BarKind.ONE_LINE], [BarKind.PLAIN_LINE]). */
fun oneLineBanner(kind: BarKind): Boolean = kind == BarKind.ONE_LINE || kind == BarKind.PLAIN_LINE

/**
 * The text in the middle of the two-line banner's first line: the screen's title where it has one
 * (sim-53, Text Messages: "Text Messages"), the network's name otherwise (sim-43, Messages:
 * "Simulated Network"). A screen of Bold7 names itself by its task's label
 * (Activity.setTaskDescription); another app's label, which Android's apps fill with whatever they
 * like (a web page's title), never shows.
 */
fun bannerTitle(controllingPackage: String?, taskLabel: CharSequence?, network: String?): String? =
    taskLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() && controllingPackage != null && controllingPackage.startsWith("dev.bold7.") } ?: network

/**
 * The call indicator, frames 11 and 12 of net_rim_Phone-icons19x19 (theme _bs1): a call off the
 * hook ([callState] TelephonyManager.CALL_STATE_OFFHOOK), on the speaker or held as well (sim-109,
 * sim-138, sim-139), the struck microphone while muted (sim-136); nothing while it rings (sim-18).
 */
fun callImage(callState: Int, micMuted: Boolean): String? = when {
    callState != 2 -> null
    micMuted -> "banner_call_muted"
    else -> "banner_call"
}

/**
 * A setting for the test only: the call indicator shown without a call,
 * 1 for a call, 2 for a muted one, read at each change of the app in front; deleted after the test
 * and by tools/uninstall.sh.
 */
const val CALL_DEMO_SETTING = "bold7_call_demo"

/** The indicator [CALL_DEMO_SETTING]'s [value] asks for, null for none (then the real call's). */
fun callDemo(value: Int): String? = when (value) {
    1 -> callImage(2, micMuted = false)
    2 -> callImage(2, micMuted = true)
    else -> null
}

/**
 * What lies under the status bar's indicators: Android's black, nothing over the keyguard. Under
 * the Phone, the one line has no black of its own either (ONE_LINE): the Phone draws its screens
 * from the window's top edge, black for the line's 34 px and its content from y 34 (sim-109,
 * sim-141), so the 33 px of the 67 px window below the line show the Phone, not a band of black.
 */
enum class BarBackground { BLACK, NONE, ONE_LINE }

/** The opacity of the bar window's black: only [BarBackground.BLACK] has any. */
fun barAlpha(look: BarBackground): Float = if (look == BarBackground.BLACK) 1f else 0f

/**
 * By the window that sets the bar's appearance: SystemUI's own over the keyguard, nothing (this
 * window lies under the keyguard's, whose own bar is drawn there); the Phone and the Calendar, their
 * one line (both draw under it, UnderBar); any other app, the banner's black (sim-43). On the home, and over an app with no bar, the whole bar is hidden (barHidden).
 */
fun barBackground(controllingPackage: String?): BarBackground = when {
    controllingPackage == "com.android.systemui" -> BarBackground.NONE
    oneLineBanner(barKind(controllingPackage)) -> BarBackground.ONE_LINE
    else -> BarBackground.BLACK
}

/**
 * The 9900's counters by the Hub's item kind (ItemKind's names, HubContract.KINDS_URI), frames of
 * net_rim_Ribbon_UnreadCount, in the home banner's order (launcher, Banner.indicators, D): mail,
 * texts, missed calls, invitations. Another app's notifications count nowhere (unreadByKind).
 */
val KIND_IMAGES = linkedMapOf(
    "EMAIL" to "banner_count_email",
    "SMS" to "banner_count_sms",
    "MISSED_CALL" to "banner_count_missed_call",
    "INVITATION" to "banner_count_calendar",
)

/** The Hub's unread count by kind, or the showcase's while [showcase], the video's [event] played: the Hub is not asked then. */
internal fun unreadRows(showcase: Boolean, event: ShowcaseEvent? = null, hub: () -> Map<String, Int>): Map<String, Int> =
    pick(showcase, hub) { Showcase.unread(event) }

/** A counter: its image of the strip and its count; another [app]'s own icon in its image's place when it has one (AppIndicators). */
data class Counter(val image: String, val count: Int, val app: String? = null)

/** The strip's "?" bubble: another app's counter while its own icon is not drawn. */
const val OTHER_APP_IMAGE = "banner_count_other"

/**
 * Each other app's counter after the kinds' (HubContract.KINDS_URI's rows with an app, newest
 * first), as the home's banner does: the firmware's third-party apps carry their own ribbon icon
 * (App World, Facebook, Twitter, A).
 */
fun appCounters(unread: Map<String, Int>): List<Counter> =
    unread.mapNotNull { (app, n) -> n.takeIf { it > 0 }?.let { Counter(OTHER_APP_IMAGE, it, app) } }

/**
 * The counters of the banner's second line (sim-43, gsm 022) and of the lock screen's row (sim-64),
 * from the Hub's unread count by kind [unread]: one per kind with something unread, in the home's
 * order. A kind this SystemUI does not know (a newer Hub) shows nothing.
 */
fun kindCounters(unread: Map<String, Int>): List<Counter> =
    KIND_IMAGES.mapNotNull { (kind, image) -> unread[kind]?.takeIf { it > 0 }?.let { Counter(image, it) } }
