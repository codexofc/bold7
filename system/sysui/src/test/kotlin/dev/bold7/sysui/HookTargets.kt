package dev.bold7.sysui

import com.android.systemui.clipboardoverlay.ClipboardListener
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView
import com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView
import com.android.systemui.statusbar.phone.KeyguardStatusBarView
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.ScrimState

// Compiled against LineageOS 24's own SystemUI (stubs/systemui.jar): if a build renames or removes a
// member SysuiModule reaches by name, the tests stop compiling. Never run, the stubs have no code.
// GuardTest ties the class names SysuiModule writes as strings to the same jar. The groups off on
// LineageOS 24 (placecall, splash) are not here: their
// targets are BenOS's rows of hook-targets.tsv until the L6 lots rewrite them.
@Suppress("unused", "UNUSED_VARIABLE")
private fun hookTargets(
    bar: PhoneStatusBarView,
    keyguardBar: KeyguardStatusBarView,
    wifi: ModernStatusBarWifiView,
    mobile: ModernStatusBarMobileView,
    icon: StatusBarIconView,
) {
    bar.onFinishInflate()
    keyguardBar.onFinishInflate()
    // The Wi-Fi's and the mobile network's views share their parent's isIconVisible and slot.
    val modern: List<ModernStatusBarView> = listOf(wifi, mobile)
    val shown: Boolean = modern[0].isIconVisible() && icon.isIconVisible()
    val slots: List<String> = listOf(modern[0].getSlot(), icon.getSlot())
}

// The shade's scrim: the hook after, and the surface colours it sets on every state.
@Suppress("unused")
private fun scrimTargets(scrim: ScrimController) {
    scrim.updateThemeColors()
    scrim.mColors.setMainColor(0)
    scrim.mColors.setSecondaryColor(0)
    scrim.mColors.setSupportsDarkText(false)
    ScrimState.values().forEach {
        it.setShadePanelColor(0)
        it.setNotificationScrimColor(0)
        it.setBouncerSurfaceColor(0)
    }
}

// Clipboard: the listener whose onPrimaryClipChanged is skipped.
@Suppress("unused")
private fun clipboardTargets(listener: ClipboardListener) {
    listener.onPrimaryClipChanged()
}
