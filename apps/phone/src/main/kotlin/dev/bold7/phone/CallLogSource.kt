package dev.bold7.phone

import android.graphics.Bitmap
import dev.bold7.showcase.CallType
import dev.bold7.showcase.Person
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.pick

/** Everything the Phone reads of the phone's calls and contacts, or asks of Telecom: on the graph's io thread only. */
interface CallLogSource {
    fun recent(): List<LogEntry>

    /** The contacts' numbers as rows the field proposes ([LogType.CONTACT], ids negative), sorted by name. */
    fun contacts(): List<LogEntry>

    fun timed(): List<Timed>

    fun photo(number: String): Bitmap?

    fun name(number: String): String?

    fun markMissedSeen()

    /** Whether the call went to Telecom. */
    fun place(number: String): Boolean

    /** The phone row Select Contact returned, by its URI. */
    fun picked(uri: String): SpeedNumber?

    fun voiceMail(): VoiceMailResult

    fun lineNumber(): String
}

/** A value of the app's own, kept between launches (speed dial keys, timers cleared). */
interface Kept<T> {
    fun load(): T

    fun save(value: T)
}

/** The real call log, or the showcase's when [showcase] is on: the real one is not even built then. */
fun callLog(
    showcase: Boolean,
    real: () -> CallLogSource,
    now: () -> Long = System::currentTimeMillis,
    /** A showcase person's avatar ([Person.photo]) drawn, for the call screen. */
    avatar: (String) -> Bitmap? = { null },
): CallLogSource = pick(showcase, real) { ShowcaseCallLog(now, avatar) }

/** The made-up caller of CallActivity's DEMO screen: Grace Carter in the showcase (media/demo/capture.sh), a test number otherwise. */
fun demoCaller(showcase: Boolean): Pair<String, String> = if (showcase) Showcase.person(5).let { it.mobile to it.name } else "5551234" to "Essai"

fun speedDials(showcase: Boolean, real: () -> Kept<SpeedDial>): Kept<SpeedDial> = pick(showcase, real) { Fixed(SHOWCASE_SPEED_DIAL) }

fun timerStore(showcase: Boolean, real: () -> Kept<CallTimersCleared>): Kept<CallTimersCleared> = pick(showcase, real) { Fixed(CallTimersCleared()) }

/** What the showcase keeps: always [value], every save dropped. */
private class Fixed<T>(private val value: T) : Kept<T> {
    override fun load() = value

    override fun save(value: T) = Unit
}

private val SHOWCASE_SPEED_DIAL = SpeedDial(
    mapOf('m' to Showcase.person(5), 'o' to Showcase.person(1)).mapValues { (_, p) -> SpeedNumber(p.mobile, p.name) },
)

/** The made-up owner's calls (Showcase.calls), in memory: nothing is read, placed nor marked. */
private class ShowcaseCallLog(private val now: () -> Long, private val avatar: (String) -> Bitmap?) : CallLogSource {
    override fun recent(): List<LogEntry> {
        val t = now()
        return Showcase.calls.mapIndexed { i, c ->
            val p = c.person?.let(Showcase::person)
            val type = when (c.type) {
                CallType.RECEIVED -> LogType.INCOMING
                CallType.PLACED -> LogType.OUTGOING
                CallType.MISSED -> LogType.MISSED
            }
            LogEntry(i + 1L, c.number, p?.name.orEmpty(), p?.let { label(it, c.number) }.orEmpty(), type, t - c.minutesAgo * 60_000L, c.isNew)
        }.sortedByDescending { it.date }
    }

    override fun contacts(): List<LogEntry> = Showcase.people.sortedBy { it.name }.flatMap { p ->
        p.numbers.mapIndexed { k, n -> LogEntry(-1 - (p.id * 10 + k), n, p.name, label(p, n), LogType.CONTACT, 0, isNew = false) }
    }

    override fun timed() = recent().filter { it.type != LogType.MISSED }.map { e ->
        Timed(e.date, Showcase.calls[e.id.toInt() - 1].seconds.toLong())
    }

    override fun photo(number: String): Bitmap? = Showcase.byNumber(number)?.let { avatar(it.photo) }

    override fun name(number: String) = Showcase.byNumber(number)?.name

    override fun markMissedSeen() = Unit

    // Simplification (showcase data): the call pretends to go (the screen goes back as after a real one); nothing is placed.
    override fun place(number: String) = true

    override fun picked(uri: String): SpeedNumber? = null

    override fun voiceMail() = VoiceMailResult.NO_NUMBER

    override fun lineNumber() = Showcase.OWNER_NUMBER

    private fun label(p: Person, number: String) = when (number) {
        p.mobile -> "Mobile"
        p.work -> "Work"
        else -> "Home"
    }
}
