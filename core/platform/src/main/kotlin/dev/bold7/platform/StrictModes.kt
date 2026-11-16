package dev.bold7.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.Executor

/**
 * Debug builds crash on main-thread disk or network access and on leaks: the main thread
 * only renders (CONTRIBUTING.md). Call first thing in every Application.onCreate().
 *
 * Violations raised in another process and handed back through binder are logged only: on
 * the Q25, system_server's MediaTek code reads the disk on every foreground activity change
 *, and penaltyDeath() cannot tell them apart from ours.
 */
fun Context.enableStrictModeIfDebuggable() {
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectAll()
            // Direct executor: the listener runs on the offending thread, where StrictMode
            // itself would throw for penaltyDeath(), so the crash and its cause read the same.
            .penaltyListener(Executor(Runnable::run)) { violation ->
                if (isNotOurs(violation.stackTrace)) {
                    Log.w("Bold7/StrictMode", "Violation not raised by our code", violation)
                } else {
                    throw RuntimeException("StrictMode ThreadPolicy violation", violation)
                }
            }
            .build(),
    )
    // Not detectAll(): it includes non-SDK API usage, which core/platform does on purpose.
    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
            .detectActivityLeaks()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectLeakedSqlLiteObjects()
            .detectFileUriExposure()
            .detectContentUriWithoutPermission()
            .detectCredentialProtectedWhileLocked()
            .detectIncorrectContextUse()
            .detectUnsafeIntentLaunch()
            .penaltyLog()
            .penaltyDeath()
            .build(),
    )
}
