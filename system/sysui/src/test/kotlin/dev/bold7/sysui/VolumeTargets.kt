package dev.bold7.sysui

import android.content.Context
import android.os.Handler
import android.util.SparseArray
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.plugins.`VolumeDialogController$Callbacks`
import com.android.systemui.plugins.`VolumeDialogController$State`
import com.android.systemui.plugins.`VolumeDialogController$StreamState`
import com.android.systemui.volume.VolumeDialogControllerImpl
import com.android.systemui.volume.dialog.shared.model.`VolumeDialogVisibilityModel$Visible`
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogPluginViewModel
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor

// What VolumeHud reaches by name, compiled against LineageOS 24's SystemUI (stubs/systemui.jar),
// like HookTargets.kt. Never run. The lambda that launches Android's dialog: VolumeLaunchTargets.kt.
@Suppress("unused", "UNUSED_VARIABLE")
private fun volumeTargets(
    model: VolumeDialogPluginViewModel,
    visible: `VolumeDialogVisibilityModel$Visible`,
    impl: VolumeDialogControllerImpl,
    panel: VolumePanelGlobalStateInteractor,
    callbacks: `VolumeDialogController$Callbacks`,
    state: `VolumeDialogController$State`,
    stream: `VolumeDialogController$StreamState`,
) {
    val reason: Int = visible.reason
    val controller: VolumeDialogController = model.dialogVisibilityInteractor.controller
    controller.addCallback(callbacks, Handler())
    controller.getState()
    val context: Context = impl.mContext
    panel.setVisible(true)
    val streams: SparseArray<*> = state.states
    val ints: List<Int> = listOf(state.activeStream, state.ringerModeInternal, state.zenMode, stream.level, stream.levelMax)
    val flags: List<Boolean> = listOf(state.disallowAlarms, state.disallowMedia, state.disallowRinger, state.disallowSystem, stream.muted)
    callbacks.onStateChanged(state)
}
