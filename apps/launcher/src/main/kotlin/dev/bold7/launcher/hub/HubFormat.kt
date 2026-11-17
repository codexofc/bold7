package dev.bold7.launcher.hub

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The phone's zone and clock, read with the items, and the names of days and months (OS7_NAMES on the phone). */
data class HubFormat(val zone: ZoneId, val locale: Locale, val clock24: Boolean)

/**
 * The time alone, the day being in the header: "3:22 PM". The 24-hour form is our own (D): no capture
 * of a 9900 in 24 hours.
 */
fun hubTime(epochMs: Long, format: HubFormat): String = timeFormatter(format).format(Instant.ofEpochMilli(epochMs).atZone(format.zone))

private fun timeFormatter(format: HubFormat) = DateTimeFormatter.ofPattern(if (format.clock24) "HH:mm" else "h:mm a", format.locale)
