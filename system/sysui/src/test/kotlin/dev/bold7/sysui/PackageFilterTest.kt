package dev.bold7.sysui

import kotlin.test.Test
import kotlin.test.assertEquals

// On the Q25, Vector calls onPackageReady for every package loaded in SystemUI's process
// (attestmon, simprocessor, imeisettings), and a second time for com.android.systemui with a
// class loader that has no dex (KeyMapper's native libraries, first test of the status bar).
class PackageFilterTest {
    @Test fun systemUiGetsTheHooks() =
        assertEquals(PackageReady.INSTALL, packageReady("com.android.systemui", alreadyHooked = false))

    @Test fun otherPackagesOfTheProcessAreIgnored() =
        assertEquals(PackageReady.OTHER_PACKAGE, packageReady("ph.dgsd.benos.attestmon", alreadyHooked = false))

    @Test fun systemUiAgainAfterHooksIsIgnored() =
        assertEquals(PackageReady.ALREADY_HOOKED, packageReady("com.android.systemui", alreadyHooked = true))
}
