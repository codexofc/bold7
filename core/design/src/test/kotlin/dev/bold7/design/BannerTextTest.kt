package dev.bold7.design

import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class BannerTextTest {
    @Test fun theTimeIsTheClocksHoursAndMinutesAndTheDateTheDaysShortNames() {
        val t = LocalDateTime.of(2026, 11, 13, 0, 44)
        assertEquals("00:44" to "", bannerTime(t, is24Hour = true))
        assertEquals("12:44" to "AM", bannerTime(t, is24Hour = false))
        assertEquals("1:05" to "PM", bannerTime(t.withHour(13).withMinute(5), is24Hour = false))
        assertEquals("Fri 13 Nov", bannerDate(t))
    }

    @Test fun theDateStaysEnglishOnAFrenchPhone() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("Fri 13 Nov", bannerDate(LocalDateTime.of(2026, 11, 13, 0, 44)))
        } finally {
            Locale.setDefault(before)
        }
    }
}
