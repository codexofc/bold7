package dev.bold7.platform

import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import android.os.Trace
import android.util.Log
import android.view.SurfaceControl
import android.view.WindowManager
import android.window.IRemoteTransition
import android.window.IRemoteTransitionFinishedCallback
import android.window.RemoteTransition
import android.window.TransitionFilter
import android.window.TransitionInfo
import android.window.WindowAnimationState
import com.android.wm.shell.shared.IShellTransitions
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Bold7/Quickstep"

/** An app window opening, or closing back to the home, under the launcher's animation. Surface thread only. */
interface OpeningWindow {
    val width: Int
    val height: Int

    /** Uniform [scale], then offset ([x], [y]) from the window's top-left corner, at [alpha]. */
    fun place(scale: Float, x: Float, y: Float, alpha: Float)

    /** A translucent window (the switcher) leaves with the app's: kept above it for [fadeLeaving]. */
    val leaves: Boolean

    /** The translucent windows that leave, above the app's, at [alpha]. */
    fun fadeLeaving(alpha: Float)

    /** Ends the transition, the window left whole by the system. Once; nothing may be called after. */
    fun finish()

    /** The animation broke: counted, logged, and [finish]ed. */
    fun fail(e: Throwable)
}

/** Plays an opening (or a closing) on the surface thread; its first [OpeningWindow.place] is the first frame shown. */
fun interface OpeningAnimation {
    fun play(window: OpeningWindow)
}

/**
 * Launches whose opening the launcher draws: a RemoteTransition in the ActivityOptions, as
 * Launcher3 does with shell transitions, played on [handler], the surface thread. Needs
 * CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS (signature|privileged on the Q25): refused, the app
 * starts anyway with Android's animation, and the refusal is counted and logged.
 *
 * The system's finished callback is always called: the animation's own [OpeningWindow.finish],
 * or here at once if it throws or if the transition holds no app opening.
 */
class RemoteLaunches(context: Context, private val handler: Handler) {
    private val appThread = context.iApplicationThread
    private val failures = AtomicInteger()

    /**
     * Set by the first refusal of the permission: later launches go without options, no second
     * binder call and no log line per tap. The permission only comes with a reboot, which starts
     * this process again.
     */
    @Volatile var refused = false
        private set

    /** Options handing the opening to [animation]: one transition each, for one start. No splash style, as [plain]. */
    fun options(animation: OpeningAnimation): Bundle = activityOptions(animation).toBundle()

    /** The same, to add to (a PendingIntent's start); plain ones once the system refused them. */
    fun activityOptions(animation: OpeningAnimation): ActivityOptions =
        if (refused) ActivityOptions.makeBasic()
        else ActivityOptions.makeRemoteTransition(RemoteTransition(Remote(animation), appThread, "Bold7Open"))

    /**
     * [Context.startActivity] with [animation]; started again without it if the system refused
     * the options. Other failures (nothing to start, a non-exported activity) are the caller's.
     */
    fun start(context: Context, intent: Intent, animation: OpeningAnimation) {
        if (refused) return context.startActivity(intent, plain())
        try {
            context.startActivity(intent, options(animation))
        } catch (e: SecurityException) {
            context.startActivity(intent, plain()) // throws again if the options were not the cause
            refuse("${intent.component ?: intent.action}", e)
        }
    }

    /** The system refused the options for [what], which then started without them. */
    fun refuse(what: String, e: SecurityException) {
        refused = true
        failed("remote transition refused, $what opened by Android, Android's animation from now on", e)
    }

    // The recents provider's onInitialize only (a binder thread): the transition registered with the shell.
    private var homeRemote: RemoteTransition? = null

    /**
     * Hands every return to [home] from an app (Escape, End, the home key, Home Screen of the
     * switcher) to [animation], the app's windows being the ones it moves: a remote transition
     * registered with the shell, as Launcher3 does (QuickstepTransitionManager, "QuickstepLaunchHome").
     * [shell] is IShellTransitions, from ILauncherProxy.onInitialize: called at each one (a new
     * SystemUI is a new shell), it replaces its own registration. The filter travels in the
     * RemoteTransition itself since Android 17 (registerRemote takes nothing else). The shell
     * checks MANAGE_ACTIVITY_TASKS (oneway: a refusal is in SystemUI's log only); refused or
     * missing, Android animates, and the missing shell is counted and logged here.
     */
    @Synchronized
    fun animateReturnsHome(shell: IBinder?, home: ComponentName, animation: OpeningAnimation) {
        if (shell == null) return failed("no shell transitions, returns to the home animated by Android", null)
        val remote = homeRemote ?: RemoteTransition(Remote(animation, closing = true), appThread, "Bold7Home", homeFilter(home)).also { homeRemote = it }
        try {
            val transitions = IShellTransitions.Stub.asInterface(shell)
            transitions.unregisterRemote(remote) // the same shell initialised again: never two registrations
            transitions.registerRemote(remote)
            Log.i(TAG, "returns to the home handed to the launcher")
        } catch (e: RemoteException) {
            failed("returns to the home not registered, Android animates them", e)
        }
    }

    /**
     * The returns from an app to [home]: home to the front, a standard app closing or to the back.
     * No CONTAINER_ORDER_TOP on the home, unlike Launcher3: it asks for the home at index 0 of the
     * changes, which holds for the home key (home on top) but not for Back or Escape, where the app
     * that finishes is listed first and the home under it (TransitionFilter.Requirement.matches,
     * measured on the Q25 under Lineage 24): those returns fell to the shell's default handler.
     */
    private fun homeFilter(home: ComponentName) = TransitionFilter().apply {
            mNotFlags = WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY // the unlock is not a return
            mRequirements = arrayOf(
                TransitionFilter.Requirement().apply {
                    mActivityType = WindowConfiguration.ACTIVITY_TYPE_HOME
                    mTopActivity = home
                    mModes = intArrayOf(WindowManager.TRANSIT_OPEN, WindowManager.TRANSIT_TO_FRONT)
                },
                TransitionFilter.Requirement().apply {
                    mActivityType = WindowConfiguration.ACTIVITY_TYPE_STANDARD
                    mModes = intArrayOf(WindowManager.TRANSIT_CLOSE, WindowManager.TRANSIT_TO_BACK)
                },
            )
    }

    /** Counts and logs a launch that fell back to Android's animation, or to none. */
    private fun failed(what: String, e: Throwable?) {
        Log.e(TAG, "$what (${failures.incrementAndGet()} so far)", e)
    }

    companion object {
        /**
         * Options for a start Android animates, with no remote transition and no splash style: the
         * starting window is the app's own splash screen if it has one, else the
         * navigator image the SystemUI hook puts there. Never the cause of a refusal.
         */
        fun plain(): Bundle = ActivityOptions.makeBasic().toBundle()
    }

    /** The shell's binder calls, moved to [handler] at once; [closing]: the windows left for the home move. */
    private inner class Remote(private val animation: OpeningAnimation, private val closing: Boolean = false) : IRemoteTransition.Stub() {
        // Handler thread only: the opening running, and the transition it plays.
        private var opening: ShellOpening? = null
        private var token: IBinder? = null

        override fun startAnimation(token: IBinder, info: TransitionInfo, t: SurfaceControl.Transaction, finished: IRemoteTransitionFinishedCallback) {
            handler.post {
                val o = try {
                    ShellOpening(info, t, finished, closing)
                } catch (e: RuntimeException) {
                    failed("opening not set up, the app shown whole", e)
                    try {
                        t.apply()
                        finished.onTransitionFinished(null, null)
                    } catch (e: RemoteException) {
                        Log.e(TAG, "onTransitionFinished failed", e)
                    } finally {
                        info.releaseAllSurfaces()
                        t.close()
                    }
                    return@post
                }
                opening = o
                this.token = token
                o.run(animation)
            }
        }

        // As the Q25's Launcher3 (RemoteAnimationRunnerCompat): nothing merged, the merged
        // transition's surfaces released, and the opening it lands on ended at once (screen
        // turned off, another launch): the shell takes the surfaces back, our frames stop.
        override fun mergeAnimation(transition: IBinder, info: TransitionInfo, t: SurfaceControl.Transaction, mergeTarget: IBinder, finished: IRemoteTransitionFinishedCallback) {
            handler.post {
                t.close()
                info.releaseAllSurfaces()
                if (mergeTarget == token) opening?.finish()
            }
        }

        // Only for a remote registered for takeovers, which this one never is: should the shell
        // call it anyway, the windows are left whole and the transition finished at once.
        override fun takeOverAnimation(transition: IBinder, info: TransitionInfo, t: SurfaceControl.Transaction, finished: IRemoteTransitionFinishedCallback, states: Array<WindowAnimationState?>) {
            handler.post {
                try {
                    t.apply()
                    finished.onTransitionFinished(null, null)
                } catch (e: RemoteException) {
                    Log.e(TAG, "onTransitionFinished failed", e)
                } finally {
                    info.releaseAllSurfaces()
                    t.close()
                }
            }
        }

        // Another handler played the transition: nothing of ours started, nothing to end.
        override fun onTransitionConsumed(transition: IBinder, aborted: Boolean) = Unit
    }

    /**
     * One transition: the app's surfaces moved together, every task opening or brought to front
     * (home going to the back), or, [closing], every opaque task closing or sent to the back (home
     * coming to the front); [startT] carries the first frame, the later ones go on [t].
     */
    private inner class ShellOpening(
        private val info: TransitionInfo,
        private val startT: SurfaceControl.Transaction,
        private val callback: IRemoteTransitionFinishedCallback,
        private val closing: Boolean,
    ) : OpeningWindow {
        // Closing, a translucent window left (the switcher over an app) is not zoomed: it goes at once.
        private val apps = info.changes.filter {
            it.taskInfo != null && if (closing) isClosing(it.mode) && !it.hasFlags(TransitionInfo.FLAG_TRANSLUCENT) else isOpening(it.mode)
        }
        // A translucent window sent away with an app's (the switcher an app or the home was chosen
        // in) stays above until the animation lets it go: OS 7 fades the switcher out (vid-04, C).
        // Closing with no app's window (the switcher over the home, already faded by its view), none.
        private val leaving = info.changes.filter {
            it.taskInfo != null && isClosing(it.mode) && it.hasFlags(TransitionInfo.FLAG_TRANSLUCENT)
        }.takeIf { apps.isNotEmpty() }.orEmpty()
        override val leaves = leaving.isNotEmpty()
        override val width = apps.firstOrNull()?.endAbsBounds?.width() ?: 0
        override val height = apps.firstOrNull()?.endAbsBounds?.height() ?: 0
        private val t = SurfaceControl.Transaction()
        private var started = false
        private var done = false
        private val root = if (info.rootCount > 0) info.getRoot(0).offset else Point()

        fun run(animation: OpeningAnimation) {
            if (closing) {
                // What comes to the front (home, wallpaper) shows at once: the shell's start state
                // hides it in an opening transition (the home key's). The windows left go above it,
                // as Launcher3 puts them, whatever order the shell gave.
                for (c in info.changes) {
                    if (isOpening(c.mode)) startT.show(c.leash).setAlpha(c.leash, 1f)
                    else if (isClosing(c.mode) && c.hasFlags(TransitionInfo.FLAG_TRANSLUCENT)) startT.setAlpha(c.leash, 0f)
                }
                for (i in apps.indices) startT.setLayer(apps[i].leash, info.changes.size * 3 - i)
            }
            for (i in leaving.indices) startT.show(leaving[i].leash).setAlpha(leaving[i].leash, 1f).setLayer(leaving[i].leash, info.changes.size * 3 + 1 + i)
            if (apps.isEmpty()) {
                // Closing, only a translucent window left (the switcher over the home): expected.
                if (!closing) Log.w(TAG, "opening without an app window (type ${info.type}): none")
                finish()
                return
            }
            try {
                animation.play(this)
            } catch (e: RuntimeException) {
                fail(e)
            } finally {
                if (!started) {
                    started = true
                    startT.apply()
                }
            }
        }

        override fun place(scale: Float, x: Float, y: Float, alpha: Float) {
            if (done) return
            Trace.beginSection("Bold7#open")
            try {
                val tx = if (started) t else startT
                for (i in apps.indices) { // indices: no iterator per frame
                    val app = apps[i]
                    val b = app.endAbsBounds
                    // The shell's start state hides an opening window (alpha 0): set on every frame.
                    tx.setScale(app.leash, scale, scale)
                        .setPosition(app.leash, b.left - root.x + x, b.top - root.y + y)
                        .setAlpha(app.leash, alpha)
                }
                if (started) t.apply()
            } finally {
                Trace.endSection()
            }
        }

        override fun fadeLeaving(alpha: Float) {
            if (done || !leaves) return
            val tx = if (started) t else startT
            for (i in leaving.indices) tx.setAlpha(leaving[i].leash, alpha)
            if (started) t.apply()
        }

        override fun fail(e: Throwable) {
            failed("opening animation failed, the app shown whole", e)
            finish()
        }

        override fun finish() {
            if (done) return
            done = true
            if (!started) {
                started = true
                startT.apply()
            }
            // Ended before its last frame (a merged launch, the screen off), an opening would leave its
            // window at the alpha of the frame it stopped on: the finish transaction below puts back
            // scale, crop and position but not alpha.
            if (!closing && started) {
                for (i in apps.indices) t.setAlpha(apps[i].leash, 1f)
                t.apply()
            }
            try {
                // The system's finish transaction puts every surface back: scale, crop, position.
                callback.onTransitionFinished(null, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "onTransitionFinished failed", e)
            }
            info.releaseAllSurfaces() // our references only, as Launcher3 does
            startT.close()
            t.close()
        }
    }
}

private fun isOpening(mode: Int) = mode == WindowManager.TRANSIT_OPEN || mode == WindowManager.TRANSIT_TO_FRONT

private fun isClosing(mode: Int) = mode == WindowManager.TRANSIT_CLOSE || mode == WindowManager.TRANSIT_TO_BACK
