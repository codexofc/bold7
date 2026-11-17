package dev.bold7.sysui

import android.content.Context
import android.graphics.drawable.Drawable
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy
import com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.policy.BluetoothControllerImpl
import com.android.systemui.statusbar.policy.LocationControllerImpl

// What StatusIcons reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar),
// like HookTargets.kt. Never run, the stubs have no code.
@Suppress("unused", "UNUSED_VARIABLE")
private fun statusIconsTargets(view: StatusBarIconView, icon: StatusBarIcon, container: StatusIconContainer) {
    val android: Drawable? = view.getIcon(icon)
    view.maybeUpdateIconScaleDimens()
    view.mIconScale = 1f
    val slot: String = view.getSlot()
    val redrawn: Boolean = view.updateDrawable(true)
    val state: Int = view.mVisibleState
    container.mIconSpacing = Banner.SPACING
}

// The two slots whose image follows a state Android does not show (StatusIconStates).
@Suppress("unused", "UNUSED_VARIABLE")
private fun statusIconStatesTargets(policy: PhoneStatusBarPolicy) {
    policy.updateBluetooth()
    policy.onLocationActiveChanged()
    (policy.mIconController as StatusBarIconControllerImpl).setIconVisibility(policy.mSlotBluetooth, true)
    val slot: String = policy.mSlotLocation
    val bluetooth = policy.mBluetooth as BluetoothControllerImpl
    val connected: Boolean = bluetooth.mEnabled && bluetooth.mConnectionState == 2 && (bluetooth.mIsActive || !bluetooth.mAudioProfileOnly)
    val location = policy.mLocationController as LocationControllerImpl
    val active: Boolean = location.mAreActiveLocationRequests
    val context: Context = location.mContext
    val privacyChip: Boolean = policy.mPrivacyItemController.privacyConfig.locationAvailable
}
