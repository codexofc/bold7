package dev.bold7.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class TimersTest {
    // Calls of the log, newest first: when (ms) and how long (s); a missed call lasts 0.
    private val calls = listOf(Timed(5_000, 0), Timed(4_000, 6), Timed(3_000, 125), Timed(1_000, 3600))

    @Test fun lastCallIsTheNewestCallTalkedAndTotalTheirSum() {
        val t = timers(calls, CallTimersCleared())
        assertEquals(6, t.lastCall)
        assertEquals(3731, t.total)
    }

    @Test fun clearTimerCountsFromItsMoment() {
        // Guide 7.1 p. 76: Status > Last Call or Total Calls > Clear Timer.
        assertEquals(0, timers(calls, CallTimersCleared(lastCall = 4_500)).lastCall)
        assertEquals(3731, timers(calls, CallTimersCleared(lastCall = 4_500)).total)
        assertEquals(131, timers(calls, CallTimersCleared(total = 2_000)).total)
        assertEquals(6, timers(calls, CallTimersCleared(total = 2_000)).lastCall)
        assertEquals(CallTimers(0, 0), timers(emptyList(), CallTimersCleared()))
    }

    @Test fun clearedMomentsKeptAsText() {
        val c = CallTimersCleared(lastCall = 12, total = 34)
        assertEquals(c, CallTimersCleared.decode(c.encode()))
        assertEquals(CallTimersCleared(), CallTimersCleared.decode(""))
        assertEquals(CallTimersCleared(), CallTimersCleared.decode("x,y"))
    }
}
