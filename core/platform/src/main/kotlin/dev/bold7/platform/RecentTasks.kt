package dev.bold7.platform

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.RemoteException
import android.util.Log

private const val FRAMES_TAG = "Bold7/Frames"

/**
 * A task of the recents list: its app's package (its icon in the app switcher), its name;
 * [lastActive] moves each time it is used; [activity] is the class of its base activity (what tells
 * the launcher's Options from its home).
 */
data class RecentTask(val taskId: Int, val packageName: String, val label: String, val lastActive: Long, val activity: String? = null)

/**
 * The system's recent tasks as the app switcher uses them; [SystemRecentTasks] on the phone, a
 * fake in the tests. Every call but [watch]'s callbacks blocks on binder: io only, in order.
 */
interface RecentTasks {
    /** The user's apps among the recent tasks, most recently used first, [max] at most; null if the system refused. */
    fun list(max: Int): List<RecentTask>?

    /**
     * Brings [taskId] back to the front, as Launcher3 does from its recents, its window handed to
     * [animation] if any; false if refused.
     */
    fun open(taskId: Int, animation: OpeningAnimation? = null): Boolean

    /** Closes [taskId], its process too if it has no other task; false if it was already gone or refused. */
    fun remove(taskId: Int): Boolean

    /** Until closed, on a binder thread: [onRemoved] gets each task the system removes. */
    fun watch(onRemoved: (Int) -> Unit): AutoCloseable
}

/**
 * The real [RecentTasks], for the recents provider: ATMS allows it the full recents list
 * (isCallerRecents), START_TASKS_FROM_RECENTS, REMOVE_TASKS and MANAGE_ACTIVITY_TASKS for the
 * task stack listener. No snapshot is read: the switcher shows icons.
 *
 * Every system call is guarded as Launcher3 guards it: RemoteException, and RuntimeException,
 * which ATMS throws for a task gone meanwhile (IllegalArgumentException "Task … not found") or
 * a permission refused (SecurityException). Each is logged and becomes the typed "nothing"
 * result of the call; an exception here would take down the process, quickstep service included.
 */
class SystemRecentTasks(
    context: Context,
    private val launches: RemoteLaunches,
) : RecentTasks {
    private val atm = ActivityTaskManager.getService()
    private val am = context.getSystemService(ActivityManager::class.java)
    private val pm = context.packageManager

    // The system already leaves out home, recents and dream tasks, and apps uninstalled or
    // disabled (RECENT_IGNORE_UNAVAILABLE). It keeps the most recent task that asked to stay out
    // of the recents (a dialog-like activity, a setup screen): left out here, hence one more asked.
    override fun list(max: Int): List<RecentTask>? = guarded("getRecentTasks", null) {
        @Suppress("DEPRECATION") // the recents provider is exempt from its restriction (ATMS.isGetTasksAllowed)
        am.getRecentTasks(max + 1, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
            .filter { it.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0 }
            .mapNotNull { info ->
                val pkg = (info.baseIntent.component ?: info.baseActivity)?.packageName ?: return@mapNotNull null
                RecentTask(info.taskId, pkg, label(pkg) ?: info.taskDescription?.label ?: pkg, info.lastActiveTime, (info.baseIntent.component ?: info.baseActivity)?.className)
            }
            .take(max)
    }

    /** The app's name, as shown under a frame; null for a package this one cannot see. */
    private fun label(pkg: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    // Options refused (no CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS): started again with the plain ones,
    // counted by [launches] once that start went through.
    override fun open(taskId: Int, animation: OpeningAnimation?): Boolean = guarded("startActivityFromRecents($taskId)", false) {
        val result = try {
            atm.startActivityFromRecents(taskId, animation?.takeUnless { launches.refused }?.let(launches::options) ?: RemoteLaunches.plain())
        } catch (e: SecurityException) {
            if (animation == null || launches.refused) throw e
            atm.startActivityFromRecents(taskId, RemoteLaunches.plain()).also { launches.refuse("task $taskId", e) }
        }
        if (result < ActivityManager.START_SUCCESS) Log.w(FRAMES_TAG, "task $taskId not started from the recents: $result")
        result >= ActivityManager.START_SUCCESS
    }

    override fun remove(taskId: Int): Boolean = guarded("removeTask($taskId)", false) { atm.removeTask(taskId) }

    override fun watch(onRemoved: (Int) -> Unit): AutoCloseable {
        val listener = object : TaskStackListener() {
            override fun onTaskRemoved(taskId: Int) = onRemoved(taskId)
        }
        val registered = guarded("registerTaskStackListener, the tasks are followed only while a switcher shows", false) {
            atm.registerTaskStackListener(listener)
            true
        }
        if (!registered) return AutoCloseable {}
        return AutoCloseable { guarded("unregisterTaskStackListener", Unit) { atm.unregisterTaskStackListener(listener) } }
    }

    /** [block], or [failed] with a log line naming [what] if the system threw. */
    private inline fun <T> guarded(what: String, failed: T, block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        Log.e(FRAMES_TAG, "$what failed", e)
        failed
    } catch (e: RuntimeException) {
        Log.e(FRAMES_TAG, "$what failed", e)
        failed
    }
}
