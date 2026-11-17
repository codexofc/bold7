package dev.bold7.sysui

import android.content.Context
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl
import com.android.systemui.statusbar.notification.interruption.`VisualInterruptionDecisionProviderImpl$DecisionImpl`
import com.android.systemui.statusbar.notification.interruption.`VisualInterruptionDecisionProviderImpl$LoggableDecision`

// What installHeadsUp reaches by name, compiled against LineageOS 24's SystemUI
// (stubs/systemui.jar), like HookTargets.kt. Never run, the stubs have no code.
@Suppress("unused", "UNUSED_VARIABLE")
private fun headsUpTargets(provider: VisualInterruptionDecisionProviderImpl, entry: NotificationEntry) {
    val android: `VisualInterruptionDecisionProviderImpl$LoggableDecision` = provider.makeLoggablePeekDecision(entry)
    val interrupt: Boolean = android.decision.shouldInterrupt
    val context: Context = provider.context
    val sbn: StatusBarNotification = entry.mSbn
    val cut = `VisualInterruptionDecisionProviderImpl$LoggableDecision`(`VisualInterruptionDecisionProviderImpl$DecisionImpl`(CUT_REASON, false), null, null, false)
}
