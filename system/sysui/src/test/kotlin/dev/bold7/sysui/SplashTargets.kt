package dev.bold7.sysui

import android.window.StartingWindowInfo
import com.android.wm.shell.startingsurface.phone.PhoneStartingWindowTypeAlgorithm

// What installSplash reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar),
// like HookTargets.kt. Never run.
@Suppress("unused", "UNUSED_VARIABLE")
private fun splashTargets(algorithm: PhoneStartingWindowTypeAlgorithm, info: StartingWindowInfo) {
    val type: Int = algorithm.getSuggestedWindowType(info)
}
