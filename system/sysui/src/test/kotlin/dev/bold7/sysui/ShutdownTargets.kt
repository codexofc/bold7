package dev.bold7.sysui

import android.content.Context
import com.android.systemui.globalactions.GlobalActionsImpl

// What ShutdownScreen reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar),
// like HookTargets.kt. Never run.
@Suppress("unused", "UNUSED_VARIABLE")
private fun shutdownTargets(impl: GlobalActionsImpl) {
    impl.showShutdownUi(false, null, false)
    val context: Context = impl.mContext
}
