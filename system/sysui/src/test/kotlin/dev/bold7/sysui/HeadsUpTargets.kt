package dev.bold7.sysui

import android.content.ContentResolver
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl

// What installHeadsUp reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar),
// like HookTargets.kt. Never run, the stubs have no code.
@Suppress("unused", "UNUSED_VARIABLE")
private fun headsUpTargets(provider: NotificationInterruptStateProviderImpl, entry: NotificationEntry) {
    val android: Boolean = provider.checkHeadsUp(entry, true)
    val dozing: Boolean = provider.mStatusBarStateController.isDozing
    val resolver: ContentResolver = provider.mContentResolver
    val sbn: StatusBarNotification = entry.mSbn
}
