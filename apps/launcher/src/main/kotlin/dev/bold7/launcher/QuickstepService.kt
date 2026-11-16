package dev.bold7.launcher

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.graphics.Region
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceControl
import dev.bold7.launcher.quickstep.WindowZoom
import dev.bold7.launcher.switcher.startSwitcher
import com.android.systemui.shared.recents.IOverviewProxy

private const val TAG = "Bold7/Quickstep"

// Bundle keys of OverviewProxyService.onServiceConnected (QuickStepContract, ShellSharedConstants).
private const val KEY_SYSUI_PROXY = "extra_sysui_proxy"
private const val KEY_RECENT_TASKS = "extra_shell_recent_tasks"
private const val KEY_SHELL_TRANSITIONS = "extra_shell_shell_transitions"

/** True when the task in front is this launcher's: only the focus of the switcher depends on it. */
private fun Service.onHome(): Boolean = try {
    @Suppress("DEPRECATION") // the recents provider is exempt from its restriction (ATMS.isGetTasksAllowed)
    getSystemService(ActivityManager::class.java).getRunningTasks(1).firstOrNull()?.baseActivity?.packageName == packageName
} catch (e: RuntimeException) {
    Log.w(TAG, "front task unknown", e)
    false
}

/**
 * The recents provider's service, and nothing more: SystemUI binds it by QUICKSTEP_SERVICE
 * (before unlock, hence directBootAware in the manifest) and hands over its interfaces in
 * onInitialize. BBOS 7 has no gesture: no InputMonitor is opened, no recents transition is
 * requested, a swipe up from the bottom edge does nothing. What it keeps is the role: with
 * this component named by config_recentsComponentName and bound by SystemUI, Launcher3 does not
 * take back the bottom edge and its app preview, and AppPredictionManager answers (docs/BASELINE.md).
 * Every callback is a no-op but onOverviewToggle, which opens the app switcher (it reads the
 * tasks through SystemRecentTasks, not through this binder), and onInitialize, which hands the
 * returns to the home to the launcher's transition.
 */
class QuickstepService : Service() {
    override fun onCreate() {
        super.onCreate()
        // The process may start here, before the first home: the profiles' policy is given back all the same.
        graph.profiles.attach()
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "bound by SystemUI")
        return proxy
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.w(TAG, "unbound by SystemUI")
        return false
    }

    private val proxy = object : IOverviewProxy.Stub() {
        override fun onInitialize(params: Bundle) {
            // Neither proxy is used (nothing to tell SystemUI), only checked.
            Log.i(TAG, "onInitialize: $KEY_SYSUI_PROXY=${params.getBinder(KEY_SYSUI_PROXY) != null} $KEY_RECENT_TASKS=${params.getBinder(KEY_RECENT_TASKS) != null}")
            // OS 7's zoom back to the home (WindowZoom.close), whoever closes the app.
            graph.launches.animateReturnsHome(
                params.getBinder(KEY_SHELL_TRANSITIONS),
                ComponentName(this@QuickstepService, LauncherActivity::class.java),
                WindowZoom.close,
            )
        }

        override fun onNavigationBarSurface(surface: SurfaceControl?) {
            surface?.release() // unused; an unreleased SurfaceControl leaks (StrictMode)
        }

        // The rest concerns overview, the assistant or a navigation bar the Q25 does not have.
        override fun onActiveNavBarRegionChanges(activeRegion: Region?) = Unit
        // The APP_SWITCH key reaches the recents provider here, on its up, whichever app is in
        // front, the home included: the way to the switcher without an accessibility service. The
        // Q25's Menu key is KEY_APPSELECT (measured): until the key daemon (bold7-trackpad) splits
        // it, any press of it comes here; after, only its long press.
        override fun onOverviewToggle() = startSwitcher(this@QuickstepService, fromHome = onHome())
        override fun onOverviewShown(triggeredFromAltTab: Boolean) = Unit
        override fun onOverviewHidden(triggeredFromAltTab: Boolean, triggeredFromHomeKey: Boolean) = Unit
        override fun onAssistantAvailable(available: Boolean, longPressHomeEnabled: Boolean) = Unit
        override fun onAssistantVisibilityChanged(visibility: Float) = Unit
        override fun onSystemUiStateChanged(stateFlags: Int) = Unit
        override fun onRotationProposal(rotation: Int, isValid: Boolean) = Unit
        override fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) = Unit
        override fun onSystemBarAttributesChanged(displayId: Int, behavior: Int) = Unit
        override fun onScreenTurnedOn() = Unit
        override fun onNavButtonsDarkIntensityChanged(darkIntensity: Float) = Unit
        override fun onScreenTurningOn() = Unit
        override fun onScreenTurningOff() = Unit
        override fun enterStageSplitFromRunningApp(leftOrTop: Boolean) = Unit
        override fun onTaskbarToggled() = Unit
    }
}
