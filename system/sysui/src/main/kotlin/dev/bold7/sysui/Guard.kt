package dev.bold7.sysui

import java.security.MessageDigest
import java.util.zip.ZipFile

/** One entry of an APK's zip central directory. */
data class ApkEntry(val name: String, val crc: Long, val size: Long)

/**
 * Fingerprint of MtkSystemUI.apk sha256 5f67ca21…dbeb (the one the hooks target.
 * Computed by [apkFingerprint] from the copy pulled by tools/stubs.sh; GuardTest checks both.
 */
const val EXPECTED_SYSTEMUI_FINGERPRINT = "b7288132a09ee6b42e97077afbc481fcec1aa10803c8b4960d4b5af4a045bba5"

// The SystemUI classes SysuiModule hooks, by name: the module's class loader cannot see them
// GuardTest finds each in stubs/systemui.jar, HookTargets.kt compiles the members.
internal const val STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
internal const val KEYGUARD_BAR_VIEW = "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
internal const val BATTERY_VIEW = "com.android.systemui.battery.BatteryMeterView"
internal const val SCRIM_CONTROLLER = "com.android.systemui.statusbar.phone.ScrimController"
internal const val SCRIM_STATE = "com.android.systemui.statusbar.phone.ScrimState"
internal const val WIFI_VIEW = "com.android.systemui.statusbar.StatusBarWifiView"
internal const val MOBILE_VIEW = "com.android.systemui.statusbar.StatusBarMobileView"
internal const val ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView"
internal const val VOLUME_DIALOG = "com.android.systemui.volume.VolumeDialogImpl"
/** VolumeDialogImpl.showH, which R8 left only as this static accessor (dex name). */
internal const val VOLUME_SHOW = "-\$\$Nest\$mshowH"
/** The panel of every stream's slider (VolumePanelDialog), opened from the dialog's settings button and by a broadcast. */
internal const val VOLUME_PANEL_FACTORY = "com.android.systemui.volume.VolumePanelFactory"
internal const val VOLUME_CONTROLLER = "com.android.systemui.plugins.VolumeDialogController"
internal const val VOLUME_STATE = "$VOLUME_CONTROLLER\$State"
internal const val VOLUME_STREAM_STATE = "$VOLUME_CONTROLLER\$StreamState"
internal const val ACTIONS_DIALOG = "com.android.systemui.globalactions.GlobalActionsDialogLite\$ActionsDialogLite"
internal const val ACTIONS_ADAPTER = "com.android.systemui.globalactions.GlobalActionsDialogLite\$MyAdapter"

/**
 * One of the files the bold7 module mounts, seen from SystemUI's process: our overlays are not
 * unmounted from app processes, so it exists exactly while the module is active.
 */
internal const val BOLD7_MOUNT_WITNESS = "/system_ext/overlay/Bold7SystemUI.apk"

/**
 * SHA-256 over every entry's name, CRC-32 and size, sorted by name. Reading the central directory
 * (~370 KB for 4121 entries) instead of hashing the 24 MB APK keeps SystemUI's startup cheap, and
 * any changed, added or removed file (dex, resources, layouts) still changes the result.
 */
fun apkFingerprint(entries: List<ApkEntry>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (e in entries.sortedBy { it.name }) digest.update("${e.name}\u0000${e.crc}\u0000${e.size}\n".toByteArray())
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun readApkEntries(path: String): List<ApkEntry> =
    ZipFile(path).use { zip -> zip.entries().toList().map { ApkEntry(it.name, it.crc, it.size) } }

/**
 * Hooks go in only on the exact SystemUI they were written for, never while a fault is injected,
 * and only while the bold7 module is mounted: what turns Bold7 off (the SystemUI guard of
 * service.sh, KernelSU's safe mode, tools/uninstall.sh) turns that module off, not Vector.
 */
fun hooksAllowed(actualFingerprint: String, faultInjected: Boolean, bold7Mounted: Boolean): Boolean =
    bold7Mounted && !faultInjected && actualFingerprint == EXPECTED_SYSTEMUI_FINGERPRINT

enum class PackageReady { INSTALL, OTHER_PACKAGE, ALREADY_HOOKED }

/**
 * What to do when Vector says a package is ready in SystemUI's process. Hooks go in once, for
 * SystemUI itself: other packages share the process (attestmon, simprocessor, imeisettings), and
 * com.android.systemui comes a second time with a class loader that cannot see its classes.
 * A first attempt that hooked nothing (alreadyHooked false) leaves the door open to the next one.
 */
fun packageReady(packageName: String, alreadyHooked: Boolean): PackageReady = when {
    packageName != "com.android.systemui" -> PackageReady.OTHER_PACKAGE
    alreadyHooked -> PackageReady.ALREADY_HOOKED
    else -> PackageReady.INSTALL
}
