package dev.bold7.sysui

import android.content.Context
import com.android.systemui.power.PowerNotificationWarnings
import com.android.systemui.statusbar.events.BatteryEvent
import com.android.systemui.statusbar.events.StatusEvent
import com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerImpl

// What installBattery reaches by name, compiled against LineageOS 24's SystemUI
// (stubs/systemui.jar), like HookTargets.kt. Never run.
@Suppress("unused", "UNUSED_VARIABLE")
private fun batteryTargets(scheduler: SystemStatusAnimationSchedulerImpl, event: BatteryEvent, warnings: PowerNotificationWarnings) {
    scheduler.onStatusEvent(event as StatusEvent)
    val level: Int = event.batteryLevel
    warnings.updateNotification()
    val flags: List<Boolean> = listOf(warnings.mWarning, warnings.mInvalidCharger, warnings.mPlaySound)
    val context: Context = warnings.mContext
}
