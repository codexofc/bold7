package dev.bold7.sysui

import android.app.AlertDialog
import com.android.systemui.volume.CsdWarningDialog

// What HearingWarnings reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar),
// like HookTargets.kt. Never run. VolumeDialogImpl$5 (the safe volume warning) is anonymous, out of
// Kotlin's reach: HearingWarningTest ties it to the jar.
@Suppress("unused", "UNUSED_VARIABLE")
private fun hearingWarningTargets(csd: CsdWarningDialog) {
    csd.start()
    val alert: AlertDialog = csd
}
