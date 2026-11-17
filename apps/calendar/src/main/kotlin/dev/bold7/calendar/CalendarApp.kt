package dev.bold7.calendar

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.platform.stopOrphanKeyReleases
import dev.bold7.showcase.showcaseOn
import java.time.LocalDate
import java.time.ZoneId

internal const val TAG = "Bold7/Calendar"

class CalendarApp : Application() {
    val graph by lazy { CalendarGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        stopOrphanKeyReleases()
        super.onCreate()
    }
}

val Context.graph: CalendarGraph get() = (applicationContext as CalendarApp).graph

/** The objects of the Calendar's process, built by hand (docs/ARCHITECTURE.md §5). */
class CalendarGraph(private val app: Application) {
    /** The calendar provider and the preferences: never the main thread. */
    val io = Handler(HandlerThread("bold7-calendar", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }.looper)
    private val provider by lazy { AndroidAgenda(app) }

    /** Android's provider, or the showcase's made-up week while bold7_showcase is 1 (read at each use): [io] only. */
    val agenda: AgendaSource get() = agendaFor(showcaseOn(app), { provider }, ZoneId.systemDefault(), LocalDate::now)

    /** The reminder boxes shown, key to when (Reminders.kt): read and written on [io] only. */
    private val prefs = app.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    var shown: Map<String, Long>
        get() = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
        set(value) {
            prefs.edit().clear().apply { value.forEach { (k, v) -> putLong(k, v) } }.apply()
        }

    /** The reminders waiting for their box (main thread): the box shows the first, then the next. */
    val waiting = ArrayDeque<Alert>()
}
