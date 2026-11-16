package dev.bold7.launcher.frames

import dev.bold7.design.Dimens
import dev.bold7.launcher.frames.FramesEvent.Closed
import dev.bold7.launcher.frames.FramesEvent.Synced
import dev.bold7.platform.OpeningAnimation
import dev.bold7.platform.RecentTasks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The recent tasks' state and its source, for the app switcher. Every [tasks] call runs on
 * [io], a single thread, in order. The system is followed only while a switcher shows, between
 * [start] and [stop] (counted: a switcher closing does not cut the listener of the next one
 * already open): no listener, no wake-up while none shows. A start or a removal asks for a sync,
 * and the requests waiting on [io] make a single one: the recents list again.
 *
 * Each task wears its app's name on the grid ([nameOf]), else the one the system gives it.
 *
 * [scope] must be a supervisor scope: a failure the boundary did not turn into a result (none
 * expected) goes to [onError], logged, and the next request works as usual.
 */
class FramesModel(
    private val tasks: RecentTasks,
    io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    /** The BBOS 7 name of a package (bbos7_apps.tsv), null for the task's own; called on [io]. */
    private val nameOf: (packageName: String) -> String?,
    onError: (Throwable) -> Unit,
) {
    private val mutable = MutableStateFlow(FramesState())

    /** Collected on the main thread, which only renders it. */
    val state: StateFlow<FramesState> = mutable.asStateFlow()

    private val work = io + CoroutineExceptionHandler { _, e -> onError(e) }

    // io only.
    private var watch: AutoCloseable? = null
    private var users = 0
    private var syncQueued = false

    /** A switcher shows. */
    fun start() {
        scope.launch(work) {
            users++
            if (watch == null) watch = tasks.watch(onRemoved = { requestSync() })
            requestSync()
        }
    }

    /** A switcher is gone; the last one to go closes the listener. */
    fun stop() {
        scope.launch(work) {
            if (users > 0 && --users == 0) {
                watch?.close()
                watch = null
            }
        }
    }

    /**
     * Brings [taskId] back to the front, its window grown by [animation] if any; [done] is called
     * on [io] once the system has answered, whatever it said. A task the system refused (gone
     * meanwhile) is synced away.
     */
    fun open(taskId: Int, animation: OpeningAnimation? = null, done: () -> Unit = {}) {
        scope.launch(work) {
            try {
                if (!tasks.open(taskId, animation)) requestSync()
            } finally {
                done()
            }
        }
    }

    /** The X of a task: it goes at once, the task is removed behind it. */
    fun close(taskId: Int) {
        mutable.update { reduce(it, Closed(taskId)) }
        scope.launch(work) { tasks.remove(taskId) }
    }

    /** From any thread. The sync runs after the requests already queued on [io], so a burst makes one. */
    private fun requestSync() {
        scope.launch(work) {
            if (syncQueued) return@launch
            syncQueued = true
            scope.launch(work) {
                syncQueued = false
                sync()
            }
        }
    }

    private fun sync() {
        val listed = tasks.list(Dimens.FRAMES_MAX + mutable.value.closing.size)?.map { task -> nameOf(task.packageName)?.let { task.copy(label = it) } ?: task } ?: return
        mutable.update { reduce(it, Synced(listed)) }
    }
}
