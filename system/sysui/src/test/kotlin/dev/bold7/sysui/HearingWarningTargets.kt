package dev.bold7.sysui

import android.app.AlertDialog
import android.app.Dialog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.volume.CsdWarningDialogDelegate
import com.android.systemui.volume.SafetyWarningDialogDelegate

// What HearingWarnings reaches by name, compiled against LineageOS 24's SystemUI
// (stubs/systemui.jar), like HookTargets.kt. Never run. Both warnings are SystemUIDialogs, an
// AlertDialog, built by their delegate: its onStart(Dialog) gets the dialog.
@Suppress("unused", "UNUSED_VARIABLE")
private fun hearingWarningTargets(safe: SafetyWarningDialogDelegate, csd: CsdWarningDialogDelegate, dialog: SystemUIDialog) {
    safe.onStart(dialog as Dialog)
    csd.onStart(dialog as Dialog)
    val alert: AlertDialog = dialog
}
