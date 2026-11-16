package dev.bold7.tasks

import dev.bold7.showcase.Showcase
import dev.bold7.showcase.TaskPriority
import dev.bold7.showcase.TaskStatus
import dev.bold7.showcase.pick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The tasks the screens read and write: the app's database ([TaskStore]) or the showcase's. Off the main thread. */
interface Tasks {
    fun all(): List<Task>

    fun get(id: Long): Task?

    /** Inserts a task (id 0) or rewrites it; its id, or null when refused. */
    fun save(t: Task): Long?

    fun delete(id: Long): Boolean

    /** Delete Completed; how many went. */
    fun deleteCompleted(): Int
}

/** The showcase's tasks ([showcase] on) or the [real] ones; the real source is not even built then. */
fun tasksFor(showcase: Boolean, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), real: () -> Tasks): Tasks =
    pick(showcase, real) { ShowcaseTasks(now, zone) }

/** The made-up tasks of the showcase, due around today in [zone], in memory: a save of a shown task pretends, nothing is ever written. */
class ShowcaseTasks(now: Long, zone: ZoneId) : Tasks {
    private val tasks = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).let { today ->
        Showcase.tasks.map { t ->
            Task(
                t.id, t.title,
                when (t.status) {
                    TaskStatus.NOT_STARTED -> Status.NOT_STARTED
                    TaskStatus.IN_PROGRESS -> Status.IN_PROGRESS
                    TaskStatus.COMPLETED -> Status.COMPLETED
                    TaskStatus.WAITING -> Status.WAITING
                    TaskStatus.DEFERRED -> Status.DEFERRED
                },
                when (t.priority) {
                    TaskPriority.HIGH -> Priority.HIGH
                    TaskPriority.NORMAL -> Priority.NORMAL
                    TaskPriority.LOW -> Priority.LOW
                },
                t.dueDay?.let { today.plusDays(it.toLong()).atTime(t.dueTime) },
                t.notes,
                now,
                reminder = t.remindBefore?.let { Reminder.Before(it) },
                zone = zone,
            )
        }
    }

    override fun all() = tasks

    override fun get(id: Long) = tasks.firstOrNull { it.id == id }

    override fun save(t: Task) = t.id.takeIf { it != 0L }

    override fun delete(id: Long) = false

    override fun deleteCompleted() = 0
}
