package dev.bold7.platform

import android.app.contextualsearch.ContextualSearchConfig
import android.graphics.Region
import android.os.Bundle
import android.os.IBinder
import android.os.IRemoteCallback
import android.os.RemoteException
import android.util.Log
import com.android.systemui.shared.recents.ILauncherProxy
import com.android.wm.shell.shared.IShellTransitions

private const val TAG = "Bold7/Quickstep"

// Bundle keys of LauncherProxyService.onServiceConnected: on Android 17 each binder is put under
// its own interface descriptor (QuickStepContract.addInterface, the shell's
// createExternalInterfaces), no longer under the extra_* keys of Android 14.
private const val KEY_SYSUI_PROXY = "com.android.systemui.shared.recents.ISystemUiProxy"

/**
 * What SystemUI calls on the recents provider (ILauncherProxy, oneway, on a binder thread): every
 * call a no-op but [initialized], [overviewToggled] and onUnbind, which answers at once. Here, not
 * in the launcher, because some of its parameters are hidden framework types (IRemoteCallback,
 * ContextualSearchConfig) that only this module compiles against.
 */
abstract class LauncherProxy : ILauncherProxy.Stub() {
    /** SystemUI connected: [shellTransitions] is IShellTransitions, null if SystemUI handed none. */
    protected abstract fun initialized(shellTransitions: IBinder?)

    /** The APP_SWITCH key's up, whichever app is in front. */
    protected abstract fun overviewToggled()

    final override fun onInitialize(params: Bundle) {
        // SystemUI's proxy is not used (nothing to tell SystemUI), only checked.
        Log.i(TAG, "onInitialize: ISystemUiProxy=${params.getBinder(KEY_SYSUI_PROXY) != null} keys=${params.keySet().sorted()}")
        initialized(params.getBinder(IShellTransitions.DESCRIPTOR))
    }

    final override fun onOverviewToggle() = overviewToggled()

    // SystemUI waits for this reply (1 s at most) before it binds a recents provider again.
    final override fun onUnbind(reply: IRemoteCallback?) {
        try {
            reply?.sendResult(null)
        } catch (e: RemoteException) {
            Log.w(TAG, "onUnbind reply lost", e)
        }
    }

    // The rest concerns overview, the assistant or a navigation bar the Q25 does not have.
    final override fun onActiveNavBarRegionChanges(activeRegion: Region?) = Unit
    final override fun onOverviewShown(triggeredFromAltTab: Boolean) = Unit
    final override fun onOverviewHidden(triggeredFromAltTab: Boolean, triggeredFromHomeKey: Boolean) = Unit
    final override fun onAssistantAvailable(available: Boolean, longPressHomeEnabled: Boolean) = Unit
    final override fun onAssistantVisibilityChanged(visibility: Float) = Unit
    final override fun onAssistantOverrideInvoked(invocationType: Int) = Unit
    final override fun onSystemUiStateChanged(stateFlags: Long, displayId: Int) = Unit
    final override fun onRotationProposal(rotation: Int, isValid: Boolean) = Unit
    final override fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) = Unit
    final override fun onSystemBarAttributesChanged(displayId: Int, behavior: Int) = Unit
    final override fun onTransitionModeUpdated(barMode: Int, checkBarModes: Boolean) = Unit
    final override fun onNavButtonsDarkIntensityChanged(darkIntensity: Float) = Unit
    final override fun onNavigationBarLumaSamplingEnabled(displayId: Int, enable: Boolean) = Unit
    final override fun enterStageSplitFromRunningApp(displayId: Int, leftOrTop: Boolean) = Unit
    final override fun onTaskbarToggled() = Unit
    final override fun updateWallpaperVisibility(displayId: Int, visible: Boolean) = Unit
    final override fun checkNavBarModes(displayId: Int) = Unit
    final override fun finishBarAnimations(displayId: Int) = Unit
    final override fun touchAutoDim(displayid: Int, reset: Boolean) = Unit
    final override fun transitionTo(displayId: Int, barMode: Int, animate: Boolean) = Unit
    final override fun appTransitionPending(pending: Boolean) = Unit
    final override fun onDisplayAddSystemDecorations(displayId: Int) = Unit
    final override fun onDisplayRemoved(displayId: Int) = Unit
    final override fun onDisplayRemoveSystemDecorations(displayId: Int) = Unit
    final override fun onActionCornerActivated(action: Int, displayId: Int) = Unit
    final override fun invokeContextualSearch(entryPoint: Int, config: ContextualSearchConfig?) = Unit
}
