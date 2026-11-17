// In SystemUI's package: ActionsDialogLiteDelegate and the actions are package-private there.
package com.android.systemui.globalactions

import android.app.Dialog
import android.os.Bundle
import android.widget.BaseAdapter

// What PowerMenuScreen reaches by name, compiled against LineageOS 24's SystemUI
// (stubs/systemui.jar), like dev.bold7.sysui's HookTargets.kt. Never run. MyAdapter.onClickItem is
// gone (R8 folded it into its click lambdas): the screen does what it did, dismiss then onPress.
@Suppress("unused", "UNUSED_VARIABLE")
private fun powerMenuTargets(delegate: `GlobalActionsDialogLite$ActionsDialogLiteDelegate`, dialog: Dialog) {
    delegate.onCreate(dialog, Bundle())
    val adapters: List<BaseAdapter> = listOf(delegate.mAdapter, delegate.mOverflowAdapter, delegate.mPowerOptionsAdapter)
    (adapters[0].getItem(0) as `GlobalActionsDialogLite$Action`).onPress()
}
