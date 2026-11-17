// In SystemUI's package: the lambda is package-private there.
package com.android.systemui.volume.dialog.ui.viewmodel

// What VolumeHud hooks, compiled against LineageOS 24's SystemUI (stubs/systemui.jar), like
// dev.bold7.sysui's VolumeTargets.kt. Never run. The lambda that launches Android's dialog gets
// each visibility through Function2.invoke; its view model is this$0.
@Suppress("unused", "UNUSED_VARIABLE")
private fun volumeLaunchTargets(launch: `VolumeDialogPluginViewModel$launchVolumeDialog$1`, visibility: Any) {
    val shown: Any? = launch.invoke(visibility, null)
    val model: VolumeDialogPluginViewModel = launch.`this$0`
}
