package dev.bold7.launcher

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.bold7.launcher.quickstep.WindowZoom
import dev.bold7.launcher.switcher.startSwitcher
import dev.bold7.platform.LauncherProxy

private const val TAG = "Bold7/Quickstep"


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
 * onInitialize (ILauncherProxy). BBOS 7 has no gesture: no InputMonitor is opened, no recents
 * transition is requested, a swipe up from the bottom edge does nothing. What it keeps is the role: with
 * this component named by config_recentsComponentName and bound by SystemUI, Launcher3 does not
 * take back the bottom edge and its app preview, and AppPredictionManager answers (docs/BASELINE.md).
 * Every callback is a no-op but onOverviewToggle, which opens the app switcher (it reads the
 * tasks through SystemRecentTasks, not through this binder), onInitialize, which hands the
 * returns to the home to the launcher's transition, and onUnbind, which answers at once.
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

    private val proxy = object : LauncherProxy() {
        // OS 7's zoom back to the home (WindowZoom.close), whoever closes the app.
        override fun initialized(shellTransitions: IBinder?) = graph.launches.animateReturnsHome(
            shellTransitions,
            ComponentName(this@QuickstepService, LauncherActivity::class.java),
            WindowZoom.close,
        )

        // The APP_SWITCH key reaches the recents provider here, on its up, whichever app is in
        // front, the home included: the way to the switcher without an accessibility service. The
        // Q25's Menu key is KEY_APPSELECT (measured): until the key daemon (bold7-trackpad) splits
        // it, any press of it comes here; after, only its long press.
        override fun overviewToggled() = startSwitcher(this@QuickstepService, fromHome = onHome())
    }
}
