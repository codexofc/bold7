package dev.bold7.clock

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceTest {
    @Test fun theFirstEighthOfTheDialIsTheFirmwaresOwnImages() {
        // minute_370_0 to _42: the hand at 0 to 42 degrees, as drawn.
        for (m in 0..7) assertEquals(Sprite(m, mirrored = false, quarter = 0), handSprite(m))
    }

    @Test fun theSecondEighthMirrorsTheFirstAcrossTheDiagonal() {
        // 48 degrees is 42 mirrored across 45, 84 is 6 mirrored.
        assertEquals(Sprite(7, mirrored = true, quarter = 0), handSprite(8))
        assertEquals(Sprite(1, mirrored = true, quarter = 0), handSprite(14))
    }

    @Test fun theOtherQuartersAreTheFirstTurned() {
        assertEquals(Sprite(0, mirrored = false, quarter = 1), handSprite(15))
        assertEquals(Sprite(5, mirrored = true, quarter = 2), handSprite(40))
        assertEquals(Sprite(1, mirrored = false, quarter = 3), handSprite(46))
        assertEquals(Sprite(0, mirrored = false, quarter = 0), handSprite(60))
    }

    @Test fun theHourHandMovesEveryTwelveMinutes() {
        assertEquals(20, hourPosition(16, 11))
        assertEquals(21, hourPosition(4, 14))
        assertEquals(59, hourPosition(23, 59))
        assertEquals(0, hourPosition(0, 11))
    }

    @Test fun thePillsSayAmOrPmAndTheDay() {
        assertEquals("PM", amPm(16))
        assertEquals("AM", amPm(0))
        assertEquals("AM", amPm(11))
        assertEquals("PM", amPm(12))
    }

    @Test fun theDaysPillAndTheBarsTime() {
        assertEquals("Fri 13", dayText(java.time.LocalDate.of(2026, 11, 13)))
        assertEquals("16:14", alarmText(Alarm(16, 14, AlarmRepeat.ON)))
        assertEquals("07:05", alarmText(Alarm(7, 5, AlarmRepeat.WEEKDAYS)))
        assertEquals(null, alarmText(Alarm(16, 14, AlarmRepeat.OFF)))
    }

    @Test fun theDigitalFaceTakesTheLargestSizeThatFits() {
        // The firmware's four sizes: digits 72, 100, 129 and 160 px, colons 20, 27, 35 and 43.
        val sets = listOf(72 to 20, 100 to 27, 129 to 35, 160 to 43)
        assertEquals(3, lcdFit(sets, 720 - 32))
        assertEquals(2, lcdFit(sets, 640 - 32))
        assertEquals(1, lcdFit(sets, 480))
        assertEquals(0, lcdFit(sets, 100))
    }

    @Test fun theDateIsDayThenMonthInEnglish() {
        assertEquals("Mon 16 Nov", dateText(java.time.LocalDate.of(2026, 11, 16)))
    }

    @Test fun theFlipCardsFillTheWidthAsOnTheSimulator() {
        // sim-332: 290 px cards, 20 px from the edge and apart, on 640; the Q25's 720 gives 330.
        assertEquals(290, BigFace.cardSide(640))
        assertEquals(330, BigFace.cardSide(720))
    }
}
