package dev.bold7.clock

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.showcase.showcaseOn

internal const val TAG = "Bold7/Clock"

/**
 * The Clock's process. Some of its components are directBootAware (the alarm's receivers, service
 * and screen): this runs before the first unlock too, so nothing here may touch the credential
 * encrypted storage; the Clock keeps everything in the device protected one ([ClockStore]).
 */
class ClockApp : Application() {
    val graph by lazy { ClockGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        super.onCreate()
    }
}

val Context.graph: ClockGraph get() = (applicationContext as ClockApp).graph

/** The objects of the Clock's process, built by hand (docs/ARCHITECTURE.md §5). */
class ClockGraph(private val app: Application) {
    /** Preferences, AlarmManager and the alarm's player: never the main thread. */
    val io = Handler(HandlerThread("bold7-clock", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }.looper)
    /** The real alarm, which the ring's receivers and service read: the screens read [keep]. */
    val store by lazy { ClockStore(app) }
    val scheduler by lazy { AlarmScheduler(app, store) }

    /** What the screens show, the showcase's while the global setting bold7_showcase is 1 (read here, on [io]). */
    val keep: ClockKeep get() = clockFor(showcaseOn(app)) { store }

    /** The stored alarm handed to AlarmManager again after a screen changed it; never in the showcase. */
    fun arm() {
        if (!showcaseOn(app)) scheduler.arm()
    }

    fun armTimer(endsAt: Long?) {
        if (!showcaseOn(app)) scheduler.armTimer(endsAt)
    }

    /** What rings now, for the ring screen (main thread): null when nothing does. */
    var ringing: Ringing? = null
        set(value) {
            field = value
            listeners.toList().forEach { it(value) }
        }
    val listeners = mutableListOf<(Ringing?) -> Unit>()

    /** Whether the ring screen is resumed (main thread): a ring then posts no card of Android (RingService). */
    var ringScreen = false
}

/**
 * An alarm or the timer ringing since [since] (wall clock ms); [alarm], the alarm's time for the bar, if it is set;
 * [showcase], the video's ring ([showcaseRing]): on screen only, its buttons reach no RingService.
 */
data class Ringing(val timer: Boolean, val since: Long, val snooze: Int, val alarm: String?, val showcase: Boolean = false)
