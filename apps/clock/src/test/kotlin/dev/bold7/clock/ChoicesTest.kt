package dev.bold7.clock

import dev.bold7.platform.zoneLabel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChoicesTest {
    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val alarmRows = listOf(OptionRow.TONE, OptionRow.VOLUME, OptionRow.SNOOZE, OptionRow.VIBRATION)

    @Test fun theDefaultsShowAsThe9900s() {
        // sim-171: Evolving Dream, 8, 5 Min., Off; sim-172: Dim Screen checked; sim-173: 3 Beeps, 8, Off; sim-170 and sim-172: Analogue.
        val s = ClockSettings()
        assertEquals(listOf("Evolving Dream", "8", "5 Min.", "Off"), alarmRows.map { shown(it, s, now) })
        assertEquals(listOf("3 Beeps", "8", "Off"), listOf(OptionRow.TIMER_TONE, OptionRow.TIMER_VOLUME, OptionRow.TIMER_VIBRATION).map { shown(it, s, now) })
        assertEquals(listOf("Analogue", "Analogue", "Analogue"), listOf(OptionRow.FACE, OptionRow.STOPWATCH_FACE, OptionRow.TIMER_FACE).map { shown(it, s, now) })
        assertTrue(checked(OptionRow.DIM, s))
    }

    @Test fun theListsAreInThe9900sOrder() {
        assertEquals(listOf("Escalating", "10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "Silent"), choices(OptionRow.VOLUME, ClockSettings()).first)
        assertEquals(listOf("30 Min.", "15 Min.", "10 Min.", "5 Min.", "1 Min.", "None"), choices(OptionRow.SNOOZE, ClockSettings()).first)
        assertEquals(3, choices(OptionRow.SNOOZE, ClockSettings()).second)
    }

    @Test fun choosingAValueWritesIt() {
        val s = ClockSettings()
        assertEquals(ESCALATING, choose(OptionRow.VOLUME, s, 0).options.volume)
        assertEquals(0, choose(OptionRow.SNOOZE, s, 5).options.snooze)
        assertEquals("Seaside.m4a", choose(OptionRow.TONE, s, 7).options.tone)
        assertEquals(true, choose(OptionRow.VIBRATION, s, 1).options.vibrate)
        assertEquals(ClockFace.DIGITAL, choose(OptionRow.FACE, s, 1).display.face)
    }

    @Test fun theTimersFieldsWriteTheTimersOptionsNotTheAlarms() {
        val s = ClockSettings()
        val t = choose(OptionRow.TIMER_VIBRATION, choose(OptionRow.TIMER_VOLUME, choose(OptionRow.TIMER_TONE, s, 7), 0), 1)
        assertEquals(TimerOptions("Seaside.m4a", ESCALATING, true), t.timer)
        assertEquals(s.options, t.options)
        // Stopwatch Face and Timer Face hold only Analogue: choosing it changes nothing.
        assertEquals(s, choose(OptionRow.TIMER_FACE, s, 0))
    }

    @Test fun dimScreenIsTheOnlyCheckBox() {
        val s = toggle(OptionRow.DIM, ClockSettings())
        assertEquals(false, s.display.dim)
        assertEquals(true, toggle(OptionRow.DIM, s).display.dim)
        assertEquals(s, toggle(OptionRow.TONE, s))
    }

    @Test fun homeTimeZoneStartsOnThePhonesOwnAndChoosingItAgainClearsTheSecondZone() {
        val own = zoneLabel(ZoneId.systemDefault().id, now)
        val (list, at) = choices(OptionRow.HOME_ZONE, ClockSettings(), now)
        assertEquals(own, list[0])
        assertEquals(0, at)
        val casa = list.indexOf("Casablanca (GMT+1)")
        assertTrue(casa > 0)
        val chosen = choose(OptionRow.HOME_ZONE, ClockSettings(), casa, now)
        assertEquals("Africa/Casablanca", chosen.display.homeZone)
        assertEquals(casa, choices(OptionRow.HOME_ZONE, chosen, now).second)
        assertNull(choose(OptionRow.HOME_ZONE, chosen, 0, now).display.homeZone)
    }

    @Test fun everyGroupHasItsRowsInThe9900sOrder() {
        assertEquals(listOf(OptionGroup.CLOCK, OptionGroup.ALARM, OptionGroup.BEDSIDE, OptionGroup.STOPWATCH, OptionGroup.TIMER), OptionRow.entries.map { it.group }.distinct())
        assertEquals(listOf(Kind.CHOICE, Kind.CHOICE, Kind.BUTTON), OptionRow.entries.filter { it.group == OptionGroup.CLOCK }.map { it.kind })
    }

    @Test fun everyToneIsOneTheModuleLaysInAlarms() {
        // The ring plays /product/media/audio/alarms/<file>: each must be in system/module/sounds.tsv.
        val tsv = File("../../system/module/sounds.tsv").readLines().filter { it.startsWith("alarms/") }.associate {
            val c = it.split('\t')
            c[0].removePrefix("alarms/") to c[2]
        }
        for ((file, title) in ALARM_TONES) assertEquals(title, tsv[file], file)
        assertTrue(DEFAULT_TONE in tsv)
        assertTrue(TIMER_TONE in tsv)
    }
}
