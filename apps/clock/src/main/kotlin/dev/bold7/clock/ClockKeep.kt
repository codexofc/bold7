package dev.bold7.clock

import dev.bold7.showcase.Showcase
import dev.bold7.showcase.ShowcaseEvent
import dev.bold7.showcase.pick

/** What the Clock's screens read and write: its preferences ([ClockStore]) or the showcase's. Off the main thread. */
interface ClockKeep {
    var settings: ClockSettings
    var snoozeUntil: Long?
    var stopwatch: Stopwatch
    var timer: Countdown
}

/** The showcase's clock ([showcase] on) or the [real] one; the real one is not even built then. */
fun clockFor(showcase: Boolean, real: () -> ClockKeep): ClockKeep = pick(showcase, real) { ShowcaseClock }

/** The showcase's alarm, set and on, the rest as a new Clock's; every write is dropped. */
object ShowcaseClock : ClockKeep {
    private val shown = Showcase.alarm.let { a ->
        ClockSettings(alarm = Alarm(a.time.hour, a.time.minute, if (!a.on) AlarmRepeat.OFF else if (a.weekdays) AlarmRepeat.WEEKDAYS else AlarmRepeat.ON))
    }

    override var settings: ClockSettings
        get() = shown
        set(_) = Unit
    override var snoozeUntil: Long?
        get() = null
        set(_) = Unit
    override var stopwatch: Stopwatch
        get() = Stopwatch()
        set(_) = Unit
    override var timer: Countdown
        get() = Countdown(0)
        set(_) = Unit
}

/** The video's ring (bold7_showcase_event alarm) of the showcase's alarm, or null: shown only, nothing plays nor is stored. */
fun showcaseRing(event: ShowcaseEvent?, now: Long): Ringing? = if (event != ShowcaseEvent.ALARM) null else ShowcaseClock.settings.let {
    Ringing(timer = false, since = now, snooze = it.options.snooze, alarm = alarmText(it.alarm), showcase = true)
}

/** What rings once the video's [story] ring changed: a real ring is never replaced nor ended by it. */
fun nextRinging(current: Ringing?, story: Ringing?): Ringing? = if (current != null && !current.showcase) current else story
