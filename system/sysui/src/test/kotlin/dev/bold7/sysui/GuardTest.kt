package dev.bold7.sysui

import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuardTest {
    @Test fun hooksWhileBold7IsMountedAndNoFaultIsInjected() {
        assertTrue(hooksAllowed(faultInjected = false, bold7Mounted = true))
        assertFalse(hooksAllowed(faultInjected = true, bold7Mounted = true))
    }

    // The SystemUI guard of service.sh, KernelSU's safe mode and tools/uninstall.sh all turn the
    // bold7 module off, not Vector: without its mounts, no hooks either.
    @Test fun noHooksWithoutBold7Mounts() = assertFalse(hooksAllowed(faultInjected = false, bold7Mounted = false))

    // The module names its targets by strings; this ties the classes of the groups LineageOS 24
    // keeps whole to its SystemUI (stubs/systemui.jar). The compiler checks the members, in
    // HookTargets.kt; TargetTableTest the table.
    @Test fun hookTargetClassesAreInLineagesSystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(STATUS_BAR_VIEW, KEYGUARD_BAR_VIEW, SCRIM_CONTROLLER, SCRIM_STATE, MODERN_ICON_VIEW, ICON_VIEW, ACTIONS_DIALOG, POWER_ACTION, CLIPBOARD_LISTENER)) {
            assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
        }
    }
}
