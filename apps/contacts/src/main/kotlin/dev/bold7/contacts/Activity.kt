package dev.bold7.contacts

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The "Recent Activity" box of a contact's card (sim-194, M for the box, D for its rows): the last
// calls and texts with the contact's numbers, read by the same providers as Phone and Messages.

enum class ActivityKind { CALL_IN, CALL_OUT, CALL_MISSED, TEXT_IN, TEXT_OUT }

data class Recent(val kind: ActivityKind, val at: Long)

const val RECENT_MAX = 5

/** The newest [max] items first. */
fun recentActivity(items: List<Recent>, max: Int = RECENT_MAX): List<Recent> = items.sortedByDescending { it.at }.take(max)

/** Whether two numbers are the same line: their last 7 digits agree (country codes and spacing differ between sources). */
fun sameNumber(a: String, b: String): Boolean {
    val x = a.filter(Char::isDigit).takeLast(7)
    return x.isNotEmpty() && x == b.filter(Char::isDigit).takeLast(7)
}

private val TIME_12 = DateTimeFormatter.ofPattern("h:mma", Locale.US)
private val TIME_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val MONTH_DAY = DateTimeFormatter.ofPattern("MM/dd", Locale.US)
private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM", Locale.US)

/** The time of day for today's items, the date for the others (as Phone's log). */
fun activityTime(at: Long, now: Long, zone: ZoneId, h24: Boolean, dayFirst: Boolean): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    return if (t.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) t.format(if (h24) TIME_24 else TIME_12)
    else t.format(if (dayFirst) DAY_MONTH else MONTH_DAY)
}
