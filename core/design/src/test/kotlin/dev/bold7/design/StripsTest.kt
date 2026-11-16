package dev.bold7.design

import kotlin.test.Test
import kotlin.test.assertEquals

// One rule for the home's banner (launcher) and the apps' (SystemUI).
class StripsTest {
    // Fills of 0 to 40 px by steps of 4: any charge lights a step, 1 to 10 % the red one.
    @Test fun batteryColumnIsTheTenthRoundedUp() = assertEquals(
        listOf(0, 1, 1, 2, 2, 3, 6, 10, 10, 10),
        listOf(0, 1, 10, 11, 20, 21, 55, 91, 100, 120).map(Strips::batteryColumn),
    )

    @Test fun unknownBatteryIsTheQuestionMark() = assertEquals(11, Strips.batteryColumn(-1))

    @Test fun signalColumnsByRadioState() {
        assertEquals(0, Strips.signalColumn(3, inService = false, radioOff = true))
        assertEquals(1, Strips.signalColumn(3, inService = false, radioOff = false))
        assertEquals(8, Strips.signalColumn(3, inService = false, radioOff = false, emergencyOnly = true))
        assertEquals(0, Strips.signalColumn(3, inService = false, radioOff = true, emergencyOnly = true))
    }

    // Android's 0 to 4 on the 9900's 0 to 5 bars (columns 2 to 7): a usable signal never looks like none.
    @Test fun androidsLevelsOnTheBars() =
        assertEquals(listOf(2, 4, 5, 6, 7, 7), (0..5).map { Strips.signalColumn(it, inService = true, radioOff = false) })

    @Test fun wifiColumnsOneToFive() = assertEquals(listOf(1, 1, 3, 5, 5), listOf(-1, 0, 2, 4, 9).map(Strips::wifiColumn))
}
