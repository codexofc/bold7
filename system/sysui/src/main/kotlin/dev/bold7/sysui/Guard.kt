package dev.bold7.sysui

// The SystemUI classes SysuiModule hooks, by name: the module's class loader cannot see them.
// Each is a row of hook-targets.tsv (TargetTableTest finds them in LineageOS's SystemUI),
// HookTargets.kt compiles the members.
internal const val STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
internal const val KEYGUARD_BAR_VIEW = "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
internal const val SCRIM_CONTROLLER = "com.android.systemui.statusbar.phone.ScrimController"
internal const val SCRIM_STATE = "com.android.systemui.statusbar.phone.ScrimState"
/** The parent of the Wi-Fi's and the mobile network's views (ModernStatusBarWifiView, ModernStatusBarMobileView), which declares isIconVisible. */
internal const val MODERN_ICON_VIEW = "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
internal const val ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView"
/** The delegate of the power menu's dialog, which holds its adapters and gets the dialog in onCreate. */
internal const val ACTIONS_DIALOG = "com.android.systemui.globalactions.GlobalActionsDialogLite\$ActionsDialogLiteDelegate"
/** One of the power menu's actions, onPress what Android's adapter runs on a click. */
internal const val POWER_ACTION = "com.android.systemui.globalactions.GlobalActionsDialogLite\$Action"

/**
 * One of the files the bold7 module mounts, seen from SystemUI's process: our overlays are not
 * unmounted from app processes, so it exists exactly while the module is active.
 */
internal const val BOLD7_MOUNT_WITNESS = "/system_ext/overlay/Bold7SystemUI.apk"

/**
 * Hooks go in never while a fault is injected, and only while the bold7 module is mounted: what
 * turns Bold7 off (the SystemUI guard of service.sh, KernelSU's safe mode, tools/uninstall.sh)
 * turns that module off, not Vector. Which ones go in is then the table's, group by group
 * (TargetTable.kt): LineageOS publishes a build a week, no fingerprint of SystemUI can hold.
 */
fun hooksAllowed(faultInjected: Boolean, bold7Mounted: Boolean): Boolean = bold7Mounted && !faultInjected

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
