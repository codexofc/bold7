package dev.bold7.platform

import java.time.Instant
import java.time.ZoneId

// The time zones the apps offer (Clock's Home Time Zone, sim-170: "Casablanca (GMT)"; the Time Zone
// of Calendar's appointment, sim-228, and of a task, sim-319), pure, one implementation for the
// three. The list is the Java zone database's city zones, ordered by offset then name (D: the
// 9900's own list is not in the captures). Tested on the JVM (ZonesTest).

private val CITY = Regex("^(Africa|America|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.+")

/** A zone's name as OS 7 shows it, "Casablanca (GMT)", "Kolkata (GMT+5:30)", "Buenos Aires (GMT-3)", at [at]. */
fun zoneLabel(id: String, at: Instant): String {
    val zone = ZoneId.of(id)
    val seconds = zone.rules.getOffset(at).totalSeconds
    val minutes = Math.abs(seconds) / 60
    val offset = if (seconds == 0) "GMT" else "GMT" + (if (seconds < 0) "-" else "+") + (minutes / 60) + (if (minutes % 60 != 0) ":%02d".format(minutes % 60) else "")
    return "${id.substringAfterLast('/').replace('_', ' ')} ($offset)"
}

/** The city zones, by offset at [at] then label, one entry per label. */
fun cityZones(at: Instant, ids: Set<String> = ZoneId.getAvailableZoneIds()): List<String> =
    ids.filter { CITY.matches(it) }
        .map { Triple(it, zoneLabel(it, at), ZoneId.of(it).rules.getOffset(at).totalSeconds) }
        .distinctBy { it.second }
        .sortedWith(compareBy({ it.third }, { it.second }))
        .map { it.first }

/** A Time Zone field's list: the city zones, with [current] first when it is not one of them (UTC, a twin label). */
fun zoneChoices(current: String, at: Instant, ids: Set<String> = ZoneId.getAvailableZoneIds()): List<String> =
    cityZones(at, ids).let { if (current in it) it else listOf(current) + it }
