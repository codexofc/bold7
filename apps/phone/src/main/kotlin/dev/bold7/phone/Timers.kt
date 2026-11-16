package dev.bold7.phone

// Status of the Phone (guide 7.1 p. 76, B: "Status > Last Call or Total Calls > Clear Timer"),
// pure: the call timers worked out from Android's call log, which keeps each call's duration.

/** A call of the log that was talked (answered or placed): when it started (ms) and its length (s). */
data class Timed(val date: Long, val seconds: Long)

/** When each timer was last cleared (ms, 0 never), the app's own: Android keeps no call timer. */
data class CallTimersCleared(val lastCall: Long = 0, val total: Long = 0) {
    fun encode() = "$lastCall,$total"

    companion object {
        fun decode(s: String): CallTimersCleared {
            val (a, b) = s.split(',').mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 } ?: return CallTimersCleared()
            return CallTimersCleared(a, b)
        }
    }
}

/** The two timers, in seconds. */
data class CallTimers(val lastCall: Long, val total: Long)

/**
 * Last Call: the newest call talked since its timer was cleared (D: a missed call is no call);
 * Total Calls: their sum since its own (D: the log keeps 500 calls, older ones drop out of the sum).
 */
fun timers(calls: List<Timed>, cleared: CallTimersCleared): CallTimers = CallTimers(
    lastCall = calls.filter { it.seconds > 0 && it.date >= cleared.lastCall }.maxByOrNull { it.date }?.seconds ?: 0,
    total = calls.filter { it.seconds > 0 && it.date >= cleared.total }.sumOf { it.seconds },
)
