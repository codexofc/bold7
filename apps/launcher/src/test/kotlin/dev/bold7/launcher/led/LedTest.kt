package dev.bold7.launcher.led

import dev.bold7.launcher.profiles.Profile
import kotlin.test.Test
import kotlin.test.assertEquals

class LedTest {
    private val idle = LedInputs(
        newInHub = false, messageLed = true, profile = Profile.NORMAL, ringing = false,
        batteryLow = false, plugged = false, inService = true, coverageLed = false,
    )

    @Test
    fun nothingToSayIsOff() = assertEquals(LedColour.OFF, ledColour(idle))

    @Test
    fun newMessageIsRedInEveryProfileThatAlerts() {
        for (p in listOf(Profile.NORMAL, Profile.LOUD, Profile.MEDIUM, Profile.SILENT, Profile.VIBRATE, null)) {
            assertEquals(LedColour.RED, ledColour(idle.copy(newInHub = true, profile = p)), "$p")
        }
    }

    @Test
    fun newMessageIsNotShownWhenTheNotifierSaysNoLed() =
        assertEquals(LedColour.OFF, ledColour(idle.copy(newInHub = true, messageLed = false)))

    @Test
    fun allAlertsOffAndPhoneCallsOnlyShowNoMessage() {
        assertEquals(LedColour.OFF, ledColour(idle.copy(newInHub = true, profile = Profile.ALERTS_OFF)))
        assertEquals(LedColour.OFF, ledColour(idle.copy(newInHub = true, profile = Profile.PHONE_ONLY)))
    }

    @Test
    fun ringingIsRedButUnderAllAlertsOff() {
        assertEquals(LedColour.RED, ledColour(idle.copy(ringing = true, messageLed = false)))
        assertEquals(LedColour.RED, ledColour(idle.copy(ringing = true, profile = Profile.PHONE_ONLY)))
        assertEquals(LedColour.OFF, ledColour(idle.copy(ringing = true, profile = Profile.ALERTS_OFF)))
    }

    @Test
    fun lowBatteryIsAmberUnpluggedWhateverTheProfile() {
        assertEquals(LedColour.AMBER, ledColour(idle.copy(batteryLow = true)))
        assertEquals(LedColour.AMBER, ledColour(idle.copy(batteryLow = true, profile = Profile.ALERTS_OFF)))
        assertEquals(LedColour.OFF, ledColour(idle.copy(batteryLow = true, plugged = true)))
    }

    @Test
    fun coverageIsGreenOnlyWhenAskedAndInService() {
        assertEquals(LedColour.GREEN, ledColour(idle.copy(coverageLed = true)))
        assertEquals(LedColour.OFF, ledColour(idle.copy(coverageLed = true, inService = false)))
    }

    @Test
    fun oneColourAtATimeRedFirst() {
        val all = idle.copy(newInHub = true, batteryLow = true, coverageLed = true)
        assertEquals(LedColour.RED, ledColour(all))
        assertEquals(LedColour.AMBER, ledColour(all.copy(newInHub = false)))
        assertEquals(LedColour.GREEN, ledColour(all.copy(newInHub = false, batteryLow = false)))
    }

    @Test
    fun valuesAreThoseBold7LedParses() =
        assertEquals(listOf("off", "red", "amber", "green"), LedColour.entries.map { it.value })
}
