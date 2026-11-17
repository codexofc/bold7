package dev.bold7.clock

import dev.bold7.platform.cityZones
import dev.bold7.platform.zoneLabel
import java.time.Instant
import java.time.ZoneId

// Clock Options, the values of its fields in the order of their open lists (sim-170 to
// sim-177, C), pure. Tested on the JVM (ChoicesTest).

/** The module's alarm tones (system/module/sounds.tsv, alarms/) and their titles, as the 9900's picker names them (A). */
val ALARM_TONES = listOf(
    "Antelope.m4a" to "Antelope",
    "Aspiration.m4a" to "Evolving Aspiration",
    "Dream.m4a" to "Evolving Dream",
    "MorningBlues.m4a" to "Morning Blues",
    "MorningLight.m4a" to "Morning Light",
    "Possibilities.m4a" to "Evolving Possibilities",
    "Revelation.m4a" to "Evolving Revelation",
    "Seaside.m4a" to "Evolving Seaside",
    "3Beeps.m4a" to "3 Beeps",
)

/** Volume's list, top to bottom: Escalating, 10 to 1, Silent (sim-174, sim-177). */
val VOLUME_LIST: List<Int> = listOf(ESCALATING) + (10 downTo 1) + SILENT

/** Snooze Time's list, top to bottom (sim-175). */
val SNOOZE_LIST: List<Int> = SNOOZE_CHOICES.reversed()

fun volumeText(v: Int): String = when (v) {
    ESCALATING -> "Escalating"
    SILENT -> "Silent"
    else -> "$v"
}

fun snoozeText(minutes: Int): String = if (minutes == 0) "None" else "$minutes Min."

fun toneText(file: String): String = ALARM_TONES.firstOrNull { it.first == file }?.second ?: file

enum class Kind { CHOICE, CHECK, BUTTON }

/** The groups of Clock Options, in the order of sim-170 to sim-173 (those with a field Android can honour). */
enum class OptionGroup { CLOCK, ALARM, BEDSIDE, STOPWATCH, TIMER }

/**
 * The rows of Clock Options (sim-170 to sim-173, C), top to bottom. Disable LED, Disable Radio
 * and Sound Profile are not here: each needs something Android keeps from an app. A face other than Analogue is offered for the clock
 * only; the stopwatch's and the timer's lists hold the one value the simulator shows.
 */
enum class OptionRow(val group: OptionGroup, val kind: Kind) {
    FACE(OptionGroup.CLOCK, Kind.CHOICE), HOME_ZONE(OptionGroup.CLOCK, Kind.CHOICE),
    WHEN_CHARGING(OptionGroup.CLOCK, Kind.CHOICE), DISMISS(OptionGroup.CLOCK, Kind.CHECK), SET_TIME(OptionGroup.CLOCK, Kind.BUTTON),
    TONE(OptionGroup.ALARM, Kind.CHOICE), VOLUME(OptionGroup.ALARM, Kind.CHOICE), SNOOZE(OptionGroup.ALARM, Kind.CHOICE),
    VIBRATION(OptionGroup.ALARM, Kind.CHOICE), TRY_ALARM(OptionGroup.ALARM, Kind.BUTTON),
    DIM(OptionGroup.BEDSIDE, Kind.CHECK),
    STOPWATCH_FACE(OptionGroup.STOPWATCH, Kind.CHOICE),
    TIMER_FACE(OptionGroup.TIMER, Kind.CHOICE), TIMER_TONE(OptionGroup.TIMER, Kind.CHOICE), TIMER_VOLUME(OptionGroup.TIMER, Kind.CHOICE),
    TIMER_VIBRATION(OptionGroup.TIMER, Kind.CHOICE), TRY_TIMER(OptionGroup.TIMER, Kind.BUTTON),
}

private val FACES = listOf("Analogue", "Digital", "Flip Clock", "LCD Digital")
private val ONLY_ANALOGUE = listOf("Analogue")
private val OFF_ON = listOf("Off", "On")
private val WHEN_CHARGING = listOf("Do Nothing", "Display Clock", "Enter Bedside Mode")

/** A choice row's open list: its values' texts and the one chosen now ([now] dates the zones' offsets). */
fun choices(row: OptionRow, s: ClockSettings, now: Instant = Instant.now()): Pair<List<String>, Int> = when (row) {
    OptionRow.FACE -> FACES to s.display.face.ordinal
    OptionRow.HOME_ZONE -> cityZones(now).let { ids ->
        // The phone's own zone first (no second zone), then the list.
        val own = ZoneId.systemDefault().id
        val shown = s.display.homeZone ?: own
        (listOf(zoneLabel(own, now)) + ids.filter { it != own }.map { zoneLabel(it, now) }) to
            (if (s.display.homeZone == null) 0 else ids.filter { it != own }.indexOf(shown) + 1).coerceAtLeast(0)
    }
    OptionRow.WHEN_CHARGING -> WHEN_CHARGING to s.display.charging.ordinal
    OptionRow.TONE -> ALARM_TONES.map { it.second } to ALARM_TONES.indexOfFirst { it.first == s.options.tone }.coerceAtLeast(0)
    OptionRow.VOLUME -> VOLUME_LIST.map(::volumeText) to VOLUME_LIST.indexOf(s.options.volume).coerceAtLeast(0)
    OptionRow.SNOOZE -> SNOOZE_LIST.map(::snoozeText) to SNOOZE_LIST.indexOf(s.options.snooze).coerceAtLeast(0)
    // sim-176: Off, On, Custom; Custom (a vibration pattern of one's own) is not offered.
    OptionRow.VIBRATION -> OFF_ON to if (s.options.vibrate) 1 else 0
    OptionRow.STOPWATCH_FACE, OptionRow.TIMER_FACE -> ONLY_ANALOGUE to 0
    OptionRow.TIMER_TONE -> ALARM_TONES.map { it.second } to ALARM_TONES.indexOfFirst { it.first == s.timer.tone }.coerceAtLeast(0)
    OptionRow.TIMER_VOLUME -> VOLUME_LIST.map(::volumeText) to VOLUME_LIST.indexOf(s.timer.volume).coerceAtLeast(0)
    OptionRow.TIMER_VIBRATION -> OFF_ON to if (s.timer.vibrate) 1 else 0
    OptionRow.SET_TIME, OptionRow.TRY_ALARM, OptionRow.TRY_TIMER, OptionRow.DIM, OptionRow.DISMISS -> emptyList<String>() to 0
}

/** The settings with value [index] of [row]'s list chosen. */
fun choose(row: OptionRow, s: ClockSettings, index: Int, now: Instant = Instant.now()): ClockSettings = when (row) {
    OptionRow.FACE -> s.copy(display = s.display.copy(face = ClockFace.entries[index]))
    OptionRow.HOME_ZONE -> {
        val own = ZoneId.systemDefault().id
        s.copy(display = s.display.copy(homeZone = if (index == 0) null else cityZones(now).filter { it != own }[index - 1]))
    }
    OptionRow.WHEN_CHARGING -> s.copy(display = s.display.copy(charging = WhenCharging.entries[index]))
    OptionRow.TONE -> s.copy(options = s.options.copy(tone = ALARM_TONES[index].first))
    OptionRow.VOLUME -> s.copy(options = s.options.copy(volume = VOLUME_LIST[index]))
    OptionRow.SNOOZE -> s.copy(options = s.options.copy(snooze = SNOOZE_LIST[index]))
    OptionRow.VIBRATION -> s.copy(options = s.options.copy(vibrate = index == 1))
    OptionRow.TIMER_TONE -> s.copy(timer = s.timer.copy(tone = ALARM_TONES[index].first))
    OptionRow.TIMER_VOLUME -> s.copy(timer = s.timer.copy(volume = VOLUME_LIST[index]))
    OptionRow.TIMER_VIBRATION -> s.copy(timer = s.timer.copy(vibrate = index == 1))
    OptionRow.STOPWATCH_FACE, OptionRow.TIMER_FACE, OptionRow.SET_TIME, OptionRow.TRY_ALARM, OptionRow.TRY_TIMER, OptionRow.DIM, OptionRow.DISMISS -> s
}

/** The value a choice row shows. */
fun shown(row: OptionRow, s: ClockSettings, now: Instant = Instant.now()): String = choices(row, s, now).let { (list, i) -> list[i] }

/** The check boxes: Dismiss Clock Automatically and Dim Screen. */
fun checked(row: OptionRow, s: ClockSettings): Boolean = when (row) {
    OptionRow.DIM -> s.display.dim
    OptionRow.DISMISS -> s.display.dismiss
    else -> false
}

fun toggle(row: OptionRow, s: ClockSettings): ClockSettings = when (row) {
    OptionRow.DIM -> s.copy(display = s.display.copy(dim = !s.display.dim))
    OptionRow.DISMISS -> s.copy(display = s.display.copy(dismiss = !s.display.dismiss))
    else -> s
}
