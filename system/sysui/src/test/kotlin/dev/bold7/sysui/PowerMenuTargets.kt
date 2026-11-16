// In SystemUI's package: ActionsDialogLite and the actions are package-private there.
package com.android.systemui.globalactions

import android.os.Bundle
import android.widget.BaseAdapter

// What PowerMenuScreen reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar),
// like dev.bold7.sysui's HookTargets.kt. Never run.
@Suppress("unused", "UNUSED_VARIABLE")
private fun powerMenuTargets(dialog: `GlobalActionsDialogLite$ActionsDialogLite`) {
    dialog.onCreate(Bundle())
    val adapters: List<`GlobalActionsDialogLite$MyAdapter`> = listOf(dialog.mAdapter, dialog.mOverflowAdapter, dialog.mPowerOptionsAdapter)
    val asBase: BaseAdapter = adapters[0]
    adapters[0].onClickItem(0)
}
