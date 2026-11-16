package dev.bold7.clock

// The Clock's stopwatch and countdown timer, pure, on the elapsed realtime clock (ms since boot,
// which never jumps with the wall clock). Tested on the JVM (WatchesTest).

/** A stopwatch: [base] ms counted before [startedAt] (null: stopped); [laps], totals at each lap, newest first. */
data class Stopwatch(val base: Long = 0, val startedAt: Long? = null, val laps: List<Long> = emptyList()) {
    val running: Boolean get() = startedAt != null

    fun elapsed(now: Long): Long = base + (startedAt?.let { now - it } ?: 0)

    fun start(now: Long) = if (running) this else copy(startedAt = now)

    fun stop(now: Long) = if (!running) this else Stopwatch(elapsed(now), null, laps)

    fun lap(now: Long) = if (!running) this else copy(laps = listOf(elapsed(now)) + laps)

    /** Each lap's own time, newest first. */
    fun lapTimes(): List<Long> = laps.mapIndexed { i, total -> total - (laps.getOrNull(i + 1) ?: 0) }

    /** Back to zero, stopped only: a running stopwatch is stopped first (the guide's reset icon, p. 204). */
    fun reset() = if (running) this else Stopwatch()
}

/** A countdown of [duration] ms: [remaining] while stopped, or ending at [endsAt] (elapsed realtime) while running. */
data class Countdown(val duration: Long, val remaining: Long = duration, val endsAt: Long? = null) {
    val running: Boolean get() = endsAt != null

    fun left(now: Long): Long = endsAt?.let { (it - now).coerceAtLeast(0) } ?: remaining

    fun done(now: Long): Boolean = running && left(now) == 0L

    fun start(now: Long) = if (running || remaining <= 0) this else copy(endsAt = now + remaining)

    fun stop(now: Long) = if (!running) this else Countdown(duration, left(now))

    fun reset() = Countdown(duration)

    /** Set Timer: a new duration, stopped. */
    fun set(duration: Long) = Countdown(duration)
}

private fun hms(totalSeconds: Long) = "%02d:%02d:%02d".format(totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)

/** The stopwatch's time, to the hundredth (sim-165, sim-169: "00:10:20.40"); the last two digits are red. */
fun watchText(ms: Long): String = hms(ms / 1000) + "." + "%02d".format(ms / 10 % 100)

/** A lap's time and total in its row (sim-169: "10:20.40"), minutes beyond the hour. */
fun lapText(ms: Long): String = "%02d:%02d.%02d".format(ms / 60_000, ms / 1000 % 60, ms / 10 % 100)

/** The timer's, to the second still under way (1 ms left shows 00:00:01). */
fun timerText(ms: Long): String = hms((ms + 999) / 1000)

/**
 * Send Results (guide p. 204, B: Menu > Send Results, then a message type): what the message says
 * (D, the guide gives no wording), the time and one line per lap, the first lap first, as the
 * stopwatch shows them ([lapText]).
 */
fun resultsText(elapsed: Long, laps: List<Long>, lapTimes: List<Long>): String =
    (listOf("Stopwatch: " + watchText(elapsed)) + laps.indices.reversed().map { i -> "Lap ${laps.size - i}: ${lapText(lapTimes[i])} (${lapText(laps[i])})" }).joinToString("\n")
