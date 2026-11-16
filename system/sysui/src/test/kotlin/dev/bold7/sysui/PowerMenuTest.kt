package dev.bold7.sysui

import dev.bold7.ui.mnemonic
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PowerMenuTest {
    private val prefix = "com.android.systemui.globalactions.GlobalActionsDialogLite$"

    @Test fun androidsActionsMapToTheDialogsButtons() {
        assertEquals(PowerAction.POWER_OFF, powerAction(prefix + "ShutDownAction"))
        assertEquals(PowerAction.RESTART, powerAction(prefix + "RestartAction"))
        assertEquals(PowerAction.EMERGENCY, powerAction(prefix + "EmergencyDialerAction"))
        assertEquals(PowerAction.EMERGENCY, powerAction(prefix + "EmergencyAffordanceAction"))
    }

    // OS 7 offered none of these: dropping an action never makes the menu more permissive.
    @Test fun otherAndroidActionsAreLeftOut() {
        for (name in listOf("LockDownAction", "ScreenshotAction", "BugReportAction", "AirplaneModeAction", "PowerOptionsAction", "SilentModeTriStateAction", "1", "4")) {
            assertNull(powerAction(prefix + name), name)
        }
    }

    // The class names are matched as strings: each must exist in the Q25's SystemUI.
    @Test fun mappedClassesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (name in listOf("ShutDownAction", "RestartAction", "EmergencyDialerAction", "EmergencyAffordanceAction")) {
            assertNotNull(jar.getEntry((prefix + name).replace('.', '/') + ".class"), name)
        }
    }

    @Test fun buttonsAreStackedPowerOffFirstAndEmergencyLast() {
        val all = PowerAction.entries.toSet()
        assertEquals(listOf(PowerButton.FULL_POWER_OFF, PowerButton.RESTART, PowerButton.CANCEL, PowerButton.EMERGENCY), powerButtons(all))
        assertEquals(listOf(PowerButton.FULL_POWER_OFF, PowerButton.CANCEL), powerButtons(setOf(PowerAction.POWER_OFF)))
        assertEquals(listOf(PowerButton.CANCEL), powerButtons(emptySet()))
    }

    @Test fun focusOpensOnCancel() {
        assertEquals(2, powerFocus(powerButtons(PowerAction.entries.toSet())))
        assertEquals(0, powerFocus(powerButtons(emptySet())))
    }

    // The underlined first letters must not collide: a letter chooses the first button it starts.
    @Test fun eachEnglishLabelHasItsOwnLetter() {
        val labels = listOf("Full Power Off", "Restart", "Cancel", "Emergency")
        assertEquals(listOf(0, 1, 2, 3), "FRCE".map { mnemonic(labels, it) })
    }
}
