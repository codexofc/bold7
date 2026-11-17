package dev.bold7.contacts

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun theNewestFiveComeFirst() {
        val all = (1..8).map { Recent(ActivityKind.CALL_IN, it * 1000L) }
        assertEquals(listOf(8000L, 7000L, 6000L, 5000L, 4000L), recentActivity(all).map { it.at })
    }

    @Test fun numbersMatchOnTheirLastSevenDigits() {
        assertTrue(sameNumber("+1 519-555-0101", "5195550101"))
        assertTrue(sameNumber("555-0101", "(519) 555 0101"))
        assertFalse(sameNumber("+1 519-555-0101", "+1 519-555-0102"))
        assertFalse(sameNumber("", "5195550101"))
    }

    @Test fun todayShowsTheTimeAndEarlierDaysTheDate() {
        val now = at(16, 15, 30)
        assertEquals("9:05AM", activityTime(at(16, 9, 5), now, zone, h24 = false, dayFirst = false))
        assertEquals("09:05", activityTime(at(16, 9, 5), now, zone, h24 = true, dayFirst = false))
        assertEquals("11/14", activityTime(at(14, 9, 5), now, zone, h24 = false, dayFirst = false))
        assertEquals("14/11", activityTime(at(14, 9, 5), now, zone, h24 = false, dayFirst = true))
    }
}
