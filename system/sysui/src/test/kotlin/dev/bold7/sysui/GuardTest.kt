package dev.bold7.sysui

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuardTest {
    private val entries = listOf(ApkEntry("classes.dex", 1, 10), ApkEntry("res/a.xml", 2, 20))

    @Test fun fingerprintIgnoresEntryOrder() =
        assertEquals(apkFingerprint(entries), apkFingerprint(entries.reversed()))

    @Test fun fingerprintChangesWithAnyCrc() =
        assertNotEquals(apkFingerprint(entries), apkFingerprint(listOf(entries[0], entries[1].copy(crc = 3))))

    @Test fun fingerprintChangesWithAnySize() =
        assertNotEquals(apkFingerprint(entries), apkFingerprint(listOf(entries[0].copy(size = 11), entries[1])))

    @Test fun fingerprintChangesWithAddedEntry() =
        assertNotEquals(apkFingerprint(entries), apkFingerprint(entries + ApkEntry("res/b.xml", 2, 20)))

    @Test fun fieldsDoNotRunIntoEachOther() =
        assertNotEquals(apkFingerprint(listOf(ApkEntry("a1", 2, 3))), apkFingerprint(listOf(ApkEntry("a", 12, 3))))

    @Test fun hooksOnlyOnTheExpectedSystemUi() {
        assertTrue(hooksAllowed(EXPECTED_SYSTEMUI_FINGERPRINT, faultInjected = false, bold7Mounted = true))
        assertFalse(hooksAllowed("0".repeat(64), faultInjected = false, bold7Mounted = true))
    }

    @Test fun injectedFaultBlocksHooksOnTheExpectedSystemUi() =
        assertFalse(hooksAllowed(EXPECTED_SYSTEMUI_FINGERPRINT, faultInjected = true, bold7Mounted = true))

    // The SystemUI guard of service.sh, KernelSU's safe mode and tools/uninstall.sh all turn the
    // bold7 module off, not Vector: without its mounts, no hooks either.
    @Test fun noHooksWithoutBold7Mounts() =
        assertFalse(hooksAllowed(EXPECTED_SYSTEMUI_FINGERPRINT, faultInjected = false, bold7Mounted = false))

    // stubs/device/MtkSystemUI.apk is the copy pulled by tools/stubs.sh, sha256 as in the Guard fingerprint.
    @Test fun expectedFingerprintIsTheQ25SystemUi() {
        val apk = File("../../stubs/device/MtkSystemUI.apk")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("5f67ca21080e5f58b181ab2859395d8a433f735d162d767dcd9e0b7539a8dbeb", sha256)
        assertEquals(EXPECTED_SYSTEMUI_FINGERPRINT, apkFingerprint(readApkEntries(apk.path)))
    }

    // The module names its targets by strings; this ties each class to the Q25's SystemUI
    // (stubs/systemui.jar). The compiler checks the members, in HookTargets.kt.
    @Test fun hookTargetClassesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(
            STATUS_BAR_VIEW, KEYGUARD_BAR_VIEW, BATTERY_VIEW, SCRIM_CONTROLLER, SCRIM_STATE, WIFI_VIEW, MOBILE_VIEW, ICON_VIEW,
            VOLUME_DIALOG, VOLUME_CONTROLLER, VOLUME_STATE, VOLUME_STREAM_STATE, ACTIONS_DIALOG, ACTIONS_ADAPTER, CLIPBOARD_LISTENER,
        )) {
            assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
        }
    }
}
