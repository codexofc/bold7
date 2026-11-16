package dev.bold7.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViolationOriginTest {

    private fun stack(vararg frames: String) = frames.map {
        val (cls, method) = it.split('#')
        StackTraceElement(cls, method, null, -1)
    }.toTypedArray()

    // Seen on the Q25: system_server reads the disk on every foreground
    // activity change and StrictMode hands the violation back to the app. The frames between
    // scheduleTopResumedActivityChanged and the binder entry point were cut from the report.
    @Test
    fun violationRaisedInSystemServerIsRemote() {
        assertTrue(
            isNotOurs(
                stack(
                    "android.os.StrictMode\$AndroidBlockGuardPolicy#onReadFromDisk",
                    "java.io.UnixFileSystem#getBooleanAttributes",
                    "java.io.File#isFile",
                    "com.mediatek.scnmodule.ScnModule#isGameApp",
                    "com.mediatek.scnmodule.ScnModule#notifyAppisGame",
                    "com.mediatek.powerhalwrapper.PowerHalWrapper#amsBoostNotify",
                    "com.mediatek.server.powerhal.PowerHalManagerImpl#amsBoostNotify",
                    "com.mediatek.server.am.AmsExtImpl#amsBoostNotify",
                    "com.mediatek.server.am.AmsExtImpl#onActivityStateChanged",
                    "com.android.server.wm.ActivityRecord#scheduleTopResumedActivityChanged",
                    "android.app.IActivityClientController\$Stub#onTransact",
                    "android.os.Binder#execTransactInternal",
                    "android.os.Binder#execTransact",
                ),
            ),
        )
    }

    @Test
    fun violationRaisedByOurCodeOnTheMainThreadIsLocal() {
        assertFalse(
            isNotOurs(
                stack(
                    "android.os.StrictMode\$AndroidBlockGuardPolicy#onReadFromDisk",
                    "java.io.UnixFileSystem#getBooleanAttributes",
                    "java.io.File#isFile",
                    "dev.bold7.launcher.IconCache#load",
                    "android.os.Handler#dispatchMessage",
                    "android.os.Looper#loop",
                    "android.app.ActivityThread#main",
                    "com.android.internal.os.ZygoteInit#main",
                ),
            ),
        )
    }

    // Ours too even without a dev.bold7 frame: the framework did the read in our process.
    // The outgoing binder call (BinderProxy) must not pass for an incoming one.
    @Test
    fun localViolationInFrameworkCodeAroundAnOutgoingBinderCallIsLocal() {
        assertFalse(
            isNotOurs(
                stack(
                    "android.os.StrictMode\$AndroidBlockGuardPolicy#onReadFromDisk",
                    "android.app.SharedPreferencesImpl#awaitLoadedLocked",
                    "android.app.SharedPreferencesImpl#getString",
                    "android.os.Binder#clearCallingIdentity",
                    "android.os.BinderProxy#transact",
                    "android.os.Handler#dispatchMessage",
                    "android.os.Looper#loop",
                    "android.app.ActivityThread#main",
                ),
            ),
        )
    }

    // Nothing proves it came from elsewhere: stay strict.
    @Test
    fun emptyStackIsLocal() {
        assertFalse(isNotOurs(emptyArray()))
    }

    // Seen on the Q25: MediaTek's boost framework, loaded in every
    // app, reads the disk on the main thread at each touch, with no frame of ours.
    @Test
    fun mediatekBoostFrameworkInOurProcessIsNotOurs() {
        assertTrue(
            isNotOurs(
                stack(
                    "android.os.StrictMode\$AndroidBlockGuardPolicy#onReadFromDisk",
                    "java.io.File#exists",
                    "com.mediatek.boostfwk.utils.Util#isGameApp",
                    "com.mediatek.boostfwk.utils.TasksUtil#isGameAPP",
                    "com.mediatek.boostfwk.BoostFwkManagerImpl#perfHint",
                    "android.view.ViewRootImpl\$ViewPostImeInputStage#processPointerEvent",
                    "android.view.ViewRootImpl\$WindowInputEventReceiver#onInputEvent",
                    "android.os.Looper#loop",
                    "android.app.ActivityThread#main",
                ),
            ),
        )
    }

    // Our code calling into a MediaTek API that reads the disk is still our fault.
    @Test
    fun mediatekCodeCalledFromOursIsOurs() {
        assertFalse(
            isNotOurs(
                stack(
                    "android.os.StrictMode\$AndroidBlockGuardPolicy#onReadFromDisk",
                    "com.mediatek.boostfwk.utils.Util#isGameApp",
                    "dev.bold7.launcher.LauncherActivity#onCreate",
                    "android.app.ActivityThread#main",
                ),
            ),
        )
    }
}
