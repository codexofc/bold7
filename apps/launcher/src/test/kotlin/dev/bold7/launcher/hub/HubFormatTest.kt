package dev.bold7.launcher.hub

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

private val PARIS = ZoneId.of("Europe/Paris")
private val US = HubFormat(PARIS, Locale.US, clock24 = false)
private val FR = HubFormat(PARIS, Locale.FRANCE, clock24 = true)

private fun at(d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(2014, 12, d, h, min, 0, 0, PARIS).toInstant().toEpochMilli()

class HubTimeTest {
    @Test fun theTimeAlone() {
        assertEquals("3:22 PM", hubTime(at(23, 15, 22), US))
        assertEquals("11:52 PM", hubTime(at(31, 23, 52), US))
        assertEquals("12:05 AM", hubTime(at(31, 0, 5), US))
    }

    @Test fun twentyFourHoursWhenThePhoneSaysSo() {
        assertEquals("15:22", hubTime(at(23, 15, 22), FR))
        assertEquals("09:05", hubTime(at(23, 9, 5), FR))
    }

    @Test fun theTimeIsTheLocalOne() {
        val utc = HubFormat(ZoneId.of("UTC"), Locale.US, false)
        assertEquals("2:22 PM", hubTime(at(23, 15, 22), utc))
    }
}
