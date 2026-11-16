package dev.bold7.launcher.home

import dev.bold7.design.Strips
import dev.bold7.hub.data.ItemKind
import dev.bold7.launcher.profiles.Profile

// The home banner of BBOS 7 (docs/BBOS7-VISUEL.md §1), pure: what each indicator shows, as a
// frame of the theme's strips; its texts are bannerTime and bannerDate of core/design. No
// android.*, tested on the JVM; BannerView draws.

/** A frame of a theme strip (net_rim_*-iconsWxH.png): its column and its row, in frames. */
data class Frame(val column: Int, val row: Int = 0)

/** The battery's frame, by the rule SystemUI's bar shares (Strips, core/design). */
fun batteryFrame(percent: Int, charging: Boolean): Frame =
    Frame(Strips.batteryColumn(percent), if (charging) Strips.BATTERY_PLUGGED_ROW else 0)

/** The signal's frame, by the rule SystemUI's bar shares (Strips, core/design). */
fun signalFrame(level: Int, inService: Boolean, radioOff: Boolean): Frame = Frame(Strips.signalColumn(level, inService, radioOff))

/** The Wi-Fi's frame (Strips, core/design). Null: not connected, not drawn. */
fun wifiFrame(level: Int?): Frame? = level?.let { Frame(Strips.wifiColumn(it)) }

/** The speaker's frame of the selected profile (net_rim_Ribbon_Profile-icons43x43.png, A). */
fun profileFrame(profile: Profile): Frame = Frame(profile.frame)

/** The mobile network's generation, from Android's network type (BannerSource maps it). */
enum class NetworkType { UNKNOWN, GPRS, EDGE, UMTS, HSPA, HSPAP, LTE, NR }

/**
 * The banner's coverage text left of the signal (Coverage, homescreen-banner.pme, A): "edge" on
 * sim-03 (C); the others as the 9900's carriers wrote them (D).
 */
fun coverage(type: NetworkType): String = when (type) {
    NetworkType.UNKNOWN -> ""
    NetworkType.GPRS -> "gprs"
    NetworkType.EDGE -> "edge"
    NetworkType.UMTS, NetworkType.HSPA -> "3G"
    NetworkType.HSPAP -> "H+"
    NetworkType.LTE -> "4G"
    NetworkType.NR -> "5G"
}

/** A count and its icon, a frame of net_rim_Ribbon_UnreadCount-icons33x29.png (A, sim-03: "2" and the missed call). */
data class Indicator(val frame: Int, val count: Int) {
    /** Its frame of the strip, made once with it: the banner draws it without allocating. */
    val strip = Frame(frame)
}

const val UNREAD_EMAIL = 0
const val UNREAD_CALENDAR = 4
const val UNREAD_SMS = 6

/** The strip's speech bubble with a "?": the header of another app in the list of notifications (D, by eye). */
const val UNREAD_OTHER = 7
const val UNREAD_MISSED_CALL = 14

/** The row's counts, in this order (D): email, texts, missed calls, invitations (unreadByKind counts nothing else). */
fun indicators(counts: Map<ItemKind, Int>): List<Indicator> = listOf(
    ItemKind.EMAIL to UNREAD_EMAIL,
    ItemKind.SMS to UNREAD_SMS,
    ItemKind.MISSED_CALL to UNREAD_MISSED_CALL,
    ItemKind.INVITATION to UNREAD_CALENDAR,
).mapNotNull { (kind, frame) -> counts[kind]?.takeIf { it > 0 }?.let { Indicator(frame, it) } }

/** Everything the banner and its indicator row show, worked out off the drawing (BannerSource). */
data class BannerState(
    val time: String = "",
    val amPm: String = "",
    val date: String = "",
    val network: String = "",
    val coverage: String = "",
    val battery: Frame = batteryFrame(-1, charging = false),
    val signal: Frame = Frame(1),
    val wifi: Frame? = null,
    val profile: Frame = Frame(0),
    /** An alarm is set, a snoozing one too (Android's next alarm clock): its indicator right of the battery (sim-337, sim-338, C). */
    val alarm: Boolean = false,
    val indicators: List<Indicator> = emptyList(),
)

/**
 * Whether a finger on the banner that moved by ([dx], [dy]) pulls it down: further than twice
 * [slop], mostly downwards. It opens the list of notifications, the swipe from
 * the top that Android's shade took (D: the 9900 opened it by a tap or a click only, sim-25).
 */
fun bannerPulled(dx: Float, dy: Float, slop: Float): Boolean = dy > 2 * slop && dy > kotlin.math.abs(dx)

/** What the trackpad can focus above the tray (sim-22, sim-25, sim-02, C). */
enum class BannerFocus { BANNER, SPEAKER, NOTIFICATIONS, SEARCH }

/**
 * The room the banner's texts get, pure (BannerRoomTest): the network's name
 * is cut by an ellipsis beyond 342 px (docs/BBOS7-VISUEL.md §1: the layout's `width=342`, centred), the
 * date and the coverage keep to what is left of the row on each side of that stretch, with 8 px of air.
 */
object BannerRoom {
    const val NETWORK = 342f
    private const val AIR = 8

    /** The network's name, centred on the banner. */
    fun network(width: Int): Float = minOf(NETWORK, width.toFloat())

    /** The date, from its x to the network's stretch. */
    fun date(width: Int): Float = ((width - network(width)) / 2 - AIR - dev.bold7.design.HomeBanner.DATE_X).coerceAtLeast(0f)

    /** The coverage, right-aligned [dev.bold7.design.HomeBanner.COVERAGE_RIGHT] from the edge, back to the network's stretch. */
    fun coverage(width: Int): Float = ((width - network(width)) / 2 - AIR - dev.bold7.design.HomeBanner.COVERAGE_RIGHT).coerceAtLeast(0f)
}
