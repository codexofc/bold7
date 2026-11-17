package dev.bold7.tasks

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.showcase.showcaseOn

internal const val TAG = "Bold7/Tasks"

class TasksApp : Application() {
    val graph by lazy { TasksGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        super.onCreate()
    }
}

val Context.graph: TasksGraph get() = (applicationContext as TasksApp).graph

/** The objects of the Tasks' process, built by hand (docs/ARCHITECTURE.md §5). */
class TasksGraph(private val app: Application) {
    /** The database's and the preferences' reads and writes, the images' decoding, the reminders: never the main thread. */
    val io = Handler(HandlerThread("bold7-tasks", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }.looper)
    /** The real tasks, which the reminders ([ringer]) read: the screens read [tasks]. */
    val store by lazy { TaskStore(app) }
    val ringer by lazy { Ringer(app, store) }

    /** The tasks of the screens, the showcase's while the global setting bold7_showcase is 1 (read here, on [io]). */
    val tasks: Tasks get() = tasksFor(showcaseOn(app)) { store }

    private val prefs by lazy { app.getSharedPreferences("options", Context.MODE_PRIVATE) }

    /** Tasks Options, read and written on [io] only. */
    var options: TaskOptions
        get() = if (showcaseOn(app)) TaskOptions() else TaskOptions(
            SortBy.entries.getOrElse(prefs.getInt("sort", 0)) { SortBy.SUBJECT },
            prefs.getInt("snooze", DEFAULT_SNOOZE).takeIf { it in SNOOZE_LIST } ?: DEFAULT_SNOOZE,
            prefs.getBoolean("confirm_delete", true),
        )
        set(o) {
            if (showcaseOn(app)) return
            prefs.edit().putInt("sort", o.sort.ordinal).putInt("snooze", o.snooze).putBoolean("confirm_delete", o.confirmDelete).apply()
        }

    /** The tasks waiting for their reminder box (main thread): the box shows the first, then the next. */
    val waiting = ArrayDeque<Long>()
}
