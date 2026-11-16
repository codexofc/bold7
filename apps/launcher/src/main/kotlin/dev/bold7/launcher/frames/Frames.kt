package dev.bold7.launcher.frames

import dev.bold7.design.Dimens
import dev.bold7.platform.RecentTask

// The recent tasks as a pure state machine: no android.*, tested on the JVM. FramesModel feeds it
// what the system reports; the app switcher of BBOS 7 reads it. The tasks'
// snapshots are never read: the switcher shows icons.

/** A task: its app's package and name, and when it was last used (as of the last sync). */
data class Frame(val taskId: Int, val packageName: String, val label: String, val lastActive: Long, val activity: String? = null)

data class FramesState(
    /** Most recently used first, [Dimens.FRAMES_MAX] at most. */
    val frames: List<Frame> = emptyList(),
    /** Closed by the user, until the system no longer lists them: a sync in between leaves them out. */
    val closing: Set<Int> = emptySet(),
)

sealed interface FramesEvent {
    /** The system's recent tasks, most recent first. */
    data class Synced(val tasks: List<RecentTask>) : FramesEvent

    /** [taskId] closed. */
    data class Closed(val taskId: Int) : FramesEvent
}

/** The tasks that get a frame: never more than [Dimens.FRAMES_MAX]. */
fun shown(tasks: List<RecentTask>, closing: Set<Int>): List<RecentTask> =
    tasks.filter { it.taskId !in closing }.take(Dimens.FRAMES_MAX)

fun reduce(state: FramesState, event: FramesEvent): FramesState = when (event) {
    is FramesEvent.Synced -> {
        val listed = event.tasks.mapTo(HashSet()) { it.taskId }
        FramesState(
            frames = shown(event.tasks, state.closing).map { Frame(it.taskId, it.packageName, it.label, it.lastActive, it.activity) },
            closing = state.closing.filterTo(HashSet()) { it in listed },
        )
    }
    is FramesEvent.Closed -> state.copy(
        frames = state.frames.filter { it.taskId != event.taskId },
        closing = state.closing + event.taskId,
    )
}
