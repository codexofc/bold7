package dev.bold7.sysui

import android.content.Context
import android.util.SparseArray
import android.widget.ImageView
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.plugins.`VolumeDialogController$State`
import com.android.systemui.plugins.`VolumeDialogController$StreamState`
import com.android.systemui.clipboardoverlay.ClipboardListener
import com.android.systemui.volume.VolumeDialogImpl
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarMobileView
import com.android.systemui.statusbar.StatusBarWifiView
import com.android.systemui.statusbar.phone.KeyguardStatusBarView
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.ScrimState

// Compiled against the Q25's own SystemUI (stubs/systemui.jar): if an OTA renames or removes a
// member SysuiModule reaches by name, the tests stop compiling. Never run, the stubs have no code.
// GuardTest ties the class names SysuiModule writes as strings to the same jar.
@Suppress("unused", "UNUSED_VARIABLE")
private fun hookTargets(
    bar: PhoneStatusBarView,
    keyguardBar: KeyguardStatusBarView,
    battery: BatteryMeterView,
    wifi: StatusBarWifiView,
    mobile: StatusBarMobileView,
    icon: StatusBarIconView,
) {
    bar.onFinishInflate()
    keyguardBar.onFinishInflate()
    battery.onBatteryLevelChanged(50, false)
    val batteryIcon: ImageView = battery.mBatteryIconView
    val shown: Boolean = wifi.isIconVisible() && mobile.isIconVisible() && icon.isIconVisible()
    val slot: String = icon.getSlot()
}

// VolumeHud: the replaced show (R8 kept showH only as this accessor), the two hooks after, and
// what it reads.
@Suppress("unused", "UNUSED_VARIABLE")
private fun volumeTargets(dialog: VolumeDialogImpl, state: `VolumeDialogController$State`) {
    VolumeDialogImpl.`-$$Nest$mshowH`(dialog, 0, false, 0)
    dialog.onStateChangedH(state)
    dialog.dismissH(0)
    val context: Context = dialog.mContext
    val controller: VolumeDialogController = dialog.mController
    controller.notifyVisible(true)
    controller.userActivity()
    val current: `VolumeDialogController$State` = dialog.mState
    val streams: SparseArray<*> = state.states
    val ss = streams[state.activeStream] as `VolumeDialogController$StreamState`
    val look = volumeLook(state.activeStream, ss.level, ss.levelMax, ss.muted, state.ringerModeInternal, VolumeOutput.LOUDSPEAKER)
}

// The shade's scrim: the hook after, and the surface colour it sets on every state.
@Suppress("unused")
private fun scrimTargets(scrim: ScrimController) {
    scrim.updateThemeColors()
    scrim.mColors.setMainColor(0)
    scrim.mColors.setSecondaryColor(0)
    scrim.mColors.setSupportsDarkText(false)
    ScrimState.values().forEach { it.setSurfaceColor(0) }
}

// Clipboard: the listener whose onPrimaryClipChanged is skipped.
@Suppress("unused")
private fun clipboardTargets(listener: ClipboardListener) {
    listener.onPrimaryClipChanged()
}
