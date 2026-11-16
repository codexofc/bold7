package dev.bold7.launcher.options

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// Date and Time's Time Zone and Language's Display Language, pure (ZonesTest).
// The zone's name is OS 7's, "Casablanca (GMT)" (sim-170, sim-228, C), as Clock's Home Time Zone
// (apps/clock, Zones.kt): the Java zone database's city zones, by offset then name (D: the 9900's
// own list is not in the captures).
//
// The same few lines as apps/clock's Zones.kt; one shared copy once a third app lists zones.

private val CITY = Regex("^(Africa|America|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.+")

/** "Casablanca (GMT)", "Kolkata (GMT+5:30)", "Buenos Aires (GMT-3)", at [at]. */
fun zoneName(id: String, at: Instant): String {
    val seconds = ZoneId.of(id).rules.getOffset(at).totalSeconds
    val minutes = Math.abs(seconds) / 60
    val offset = if (seconds == 0) "GMT" else "GMT" + (if (seconds < 0) "-" else "+") + (minutes / 60) + (if (minutes % 60 != 0) ":%02d".format(minutes % 60) else "")
    return "${id.substringAfterLast('/').replace('_', ' ')} ($offset)"
}

/** The zones Time Zone offers, by offset then name, one per name; [current] added if the list lacks it. */
fun timeZones(at: Instant, current: String, ids: Set<String> = ZoneId.getAvailableZoneIds()): List<String> {
    val list = ids.filter { CITY.matches(it) }
        .map { Triple(it, zoneName(it, at), ZoneId.of(it).rules.getOffset(at).totalSeconds) }
        .distinctBy { it.second }
        .sortedWith(compareBy({ it.third }, { it.second }))
        .map { it.first }
    if (current in list || current !in ids) return list
    val offset = ZoneId.of(current).rules.getOffset(at).totalSeconds
    val at2 = list.indexOfFirst { ZoneId.of(it).rules.getOffset(at).totalSeconds > offset }.let { if (it < 0) list.size else it }
    return list.take(at2) + current + list.drop(at2)
}

/** A language as Display Language lists it: in itself, "Français (France)", "English (United States)" (D). */
fun languageName(tag: String): String {
    val l = Locale.forLanguageTag(tag)
    return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
}

/** The languages by name, [current] added if the phone does not list it. */
fun displayLanguages(tags: List<String>, current: String): List<String> =
    (if (current in tags) tags else tags + current).distinct().sortedBy { languageName(it).lowercase(Locale.ROOT) }
