package dev.bold7.clock

// The analog face of OS 7.1's Clock, pure (sim-153, C; net_rim_bb_clock_faces_480, A). Each hand
// comes in eight images, hour_370_0 to _42 and minute_370_0 to _42: the hand at 0, 6, ... 42
// degrees from 12 o'clock, cropped, its round base at the bottom left. The 60 positions of a dial
// are those eight, mirrored across the 45 degree diagonal for the second eighth, then turned by
// quarters. Tested on the JVM (FaceTest).

/** Image [image] (0 to 7, its angle / 6), [mirrored] across the diagonal, then turned [quarter] times 90 degrees clockwise. */
data class Sprite(val image: Int, val mirrored: Boolean, val quarter: Int)

/** The sprite of a hand at [position] (0 to 59, a minute's worth of dial each). */
fun handSprite(position: Int): Sprite {
    val p = position.mod(60)
    val r = p % 15
    return if (r <= 7) Sprite(r, false, p / 15) else Sprite(15 - r, true, p / 15)
}

/** The hour hand's position: five per hour, one more every twelve minutes. */
fun hourPosition(hour: Int, minute: Int): Int = hour % 12 * 5 + minute / 12

/** The left pill (sim-153: "PM" at 16:09 with the 24 hour clock). */
fun amPm(hour: Int): String = if (hour < 12) "AM" else "PM"

/** The right pill (sim-153: "Fri 13"), OS 7's English. */
fun dayText(date: java.time.LocalDate): String =
    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + date.dayOfMonth

/** The alarm bar's time (sim-161: "16:14"), or null when the alarm is Off: the bar shows the add indicator. */
fun alarmText(alarm: Alarm): String? = if (alarm.repeat == AlarmRepeat.OFF) null else "%02d:%02d".format(alarm.hour, alarm.minute)

/**
 * The LCD digital face (Clock Face: Digital; net_rim_bb_clock_faces_480 lcddigital, A): its images
 * come in four sizes, [sets] being each one's digit width and colon width, smallest first. The
 * largest whose four digits and colon fit in [available] px (D: the firmware does not say which
 * size goes where); the smallest if none does.
 */
fun lcdFit(sets: List<Pair<Int, Int>>, available: Int): Int = sets.indexOfLast { 4 * it.first + it.second <= available }.coerceAtLeast(0)

/** The date of the Digital, Flip Clock and LCD Digital faces, top right (sim-331: "Mon 16 Nov"). */
fun dateText(date: java.time.LocalDate): String =
    dayText(date) + " " + date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)

/**
 * The measures of the faces drawn as text (sim-331 to sim-333, C, on the simulator's 640 px wide
 * screen; on the Q25's they follow the width, except the date, whose margins are pixels).
 */
object BigFace {
    const val DATE_SIZE = 31 // cap height 23 px (sim-331), grey #A0A0A0
    const val DATE_RIGHT = 10 // from the right edge to the date's last pixel (630 of 640)
    const val DATE_BASELINE = 43
    const val CARD_MARGIN = 20 // Flip Clock: two cards 290 px wide, 20 from the edge and 20 apart (sim-332)
    const val CARD_GAP = 20
    const val CARD_RADIUS = 20f / 290
    const val DIGITAL_ROOM = 0.84f // Digital: the text spans 538 of 640 (sim-331)
    const val DIGITAL_CAP = 133f / 640 // its digits' height, of the width
    const val FLIP_CAP = 175f / 290 // Flip Clock: the digits' height, of the card's side

    fun cardSide(width: Int): Int = (width - 2 * CARD_MARGIN - CARD_GAP) / 2
}
