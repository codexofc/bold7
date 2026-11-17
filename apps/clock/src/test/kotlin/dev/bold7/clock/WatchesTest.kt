package dev.bold7.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchesTest {
    @Test fun theTimerOpensAtFiveMinutesAndStarts() {
        val t = Countdown(DEFAULT_TIMER_MS)
        assertEquals(300_000, t.left(0))
        assertTrue(t.start(0).running)
    }

    @Test fun theStopwatchCountsOnlyWhileRunning() {
        val s = Stopwatch().start(1_000)
        assertEquals(500, s.elapsed(1_500))
        val stopped = s.stop(3_000)
        assertEquals(2_000, stopped.elapsed(10_000))
        assertEquals(2_500, stopped.start(20_000).elapsed(20_500))
        assertFalse(stopped.running)
        assertTrue(s.running)
    }

    @Test fun lapsKeepTheTotalAndTheirOwnTimeNewestFirst() {
        val s = Stopwatch().start(0).lap(1_000).lap(3_500)
        assertEquals(listOf(3_500L, 1_000L), s.laps)
        assertEquals(listOf(2_500L, 1_000L), s.lapTimes())
        // A lap of a stopped stopwatch is nothing.
        assertEquals(s.stop(4_000).laps, s.stop(4_000).lap(9_000).laps)
    }

    @Test fun resetForgetsEverythingOnlyStopped() {
        val running = Stopwatch().start(0).lap(1_000)
        assertEquals(running, running.reset())
        assertEquals(Stopwatch(), running.stop(2_000).reset())
    }

    @Test fun theTimerCountsDownAndHoldsWhereItStopped() {
        val t = Countdown(60_000).start(0)
        assertEquals(60_000, t.left(0))
        assertEquals(15_000, t.left(45_000))
        assertEquals(0, t.left(70_000))
        val held = t.stop(20_000)
        assertNull(held.endsAt)
        assertEquals(40_000, held.left(99_999))
        assertEquals(30_000L, held.start(30_000).endsAt!! - 40_000)
    }

    @Test fun theTimerResetsToItsDurationAndNeverStartsAtZero() {
        val t = Countdown(60_000).start(0)
        assertEquals(Countdown(60_000), t.stop(10_000).reset())
        assertEquals(Countdown(0), Countdown(0).start(5))
        assertEquals(Countdown(10_000), Countdown(60_000).set(10_000))
        assertTrue(t.done(60_000))
        assertFalse(t.done(59_999))
    }

    @Test fun timesWrittenAsOs7Does() {
        assertEquals("00:00:00.00", watchText(0))
        assertEquals("00:10:20.40", watchText(620_400))
        assertEquals("01:02:03.45", watchText(3_723_456))
        assertEquals("10:20.40", lapText(620_400))
        assertEquals("62:03.45", lapText(3_723_456))
        assertEquals("00:05:00", timerText(300_000))
        // The timer shows the second still under way.
        assertEquals("00:00:01", timerText(1))
        assertEquals("01:00:00", timerText(3_600_000))
    }

    @Test fun sendResultsListsTheTimeAndTheLapsFirstLapFirst() {
        val w = Stopwatch().start(0).lap(70_000).lap(130_500).stop(200_040)
        assertEquals(
            "Stopwatch: 00:03:20.04\nLap 1: 01:10.00 (01:10.00)\nLap 2: 01:00.50 (02:10.50)",
            resultsText(w.elapsed(0), w.laps, w.lapTimes()),
        )
        assertEquals("Stopwatch: 00:00:00.00", resultsText(0, emptyList(), emptyList()))
    }
}
