package dev.bold7.clock

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// Home Time Zone (Clock Options, sim-170: "Casablanca (GMT)"; guide p. 201: a second time zone on
// the clock), pure. Its list and its labels are core/platform's (Zones.kt), shared with Calendar
// and Tasks. Tested on the JVM (ZonesTest).

/**
 * The line under the face that tells the time at [home] ("Casablanca 11:06", D for its place), or
 * null when no second zone is chosen or it is the phone's own.
 */
fun homeLine(home: String?, phone: ZoneId, now: Instant): String? {
    if (home == null || ZoneId.of(home) == phone) return null
    val t = ZonedDateTime.ofInstant(now, ZoneId.of(home))
    return home.substringAfterLast('/').replace('_', ' ') + " " + "%02d:%02d".format(t.hour, t.minute)
}
