package dev.bold7.design

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// The texts of the 9900's home banner (docs/BBOS7-VISUEL.md §1), which the lock screen repeats
// (§10, sim-64): one rule for the launcher's banner and SystemUI's lock screen.

/** The time and its AM/PM, empty in 24 hours ("00:44" on sim-03; ampmSize=20, homescreen-banner.pme, A). */
fun bannerTime(t: LocalDateTime, is24Hour: Boolean): Pair<String, String> {
    if (is24Hour) return "%02d:%02d".format(t.hour, t.minute) to ""
    val h = (t.hour + 11) % 12 + 1
    return "%d:%02d".format(h, t.minute) to if (t.hour < 12) "AM" else "PM"
}

/** "Fri 13 Nov" (sim-03, sim-64, C), in OS 7's names whatever the phone's language. */
fun bannerDate(t: LocalDateTime): String = DateTimeFormatter.ofPattern("EEE d MMM", OS7_NAMES).format(t)
