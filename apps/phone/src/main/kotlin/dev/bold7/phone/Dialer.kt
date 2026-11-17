package dev.bold7.phone

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withHelp
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.matchesWords
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The Phone screen of BBOS 7.1, pure: the call logs under the "Find Contact or Dial a Number" field
// (ps 33246, C), where typing on the keyboard dials (guide 7.1 p. 61, 63, B). docs/BBOS7-VISUEL.md §26.

/** A call's direction; [CONTACT]: not a call, a contact's number proposed for a name typed. */
enum class LogType { INCOMING, OUTGOING, MISSED, CONTACT }

/** A call of Android's call log. [label]: the contact's number type ("Work"), empty if none. */
data class LogEntry(
    val id: Long,
    val number: String,
    val name: String,
    val label: String,
    val type: LogType,
    val date: Long,
    val isNew: Boolean,
)

/** The field's place in the focus order, above the first row. */
const val FIELD = -1

/**
 * The Phone screen: the log, the contacts' numbers ([LogType.CONTACT], sorted by name), what is
 * typed, the trackpad's focus (a row, or [FIELD]).
 */
data class Dialer(
    val log: List<LogEntry> = emptyList(),
    val contacts: List<LogEntry> = emptyList(),
    val typed: String = "",
    val focus: Int = FIELD,
) {
    /**
     * The rows shown: the whole log, or the calls that match what is typed, then, for a name, the
     * contacts' numbers it finds that no call shown has (guide 7.1 p. 61, "Type a phone number or
     * contact name", B; the order and the rows D).
     */
    val shown: List<LogEntry> = if (typed.isEmpty()) log else log.filter { it.matches(typed) }.let { calls ->
        val name = number(typed)
        if (name.none { it.isLetter() }) calls
        else calls + contacts.filter { c -> matchesWords(c.name, name) && calls.none { sameNumber(it.number, c.number) } }
    }
    val focused: LogEntry? get() = shown.getOrNull(focus)

    /** Escape: clears what is typed, then leaves (null). */
    fun back(): Dialer? = if (typed.isEmpty()) null else copy(typed = "").refocused(null)

    /**
     * What the Send key calls: the number typed; a name typed calls the call found, else its keypad
     * digits (guide p. 63); nothing typed, the focused call.
     */
    fun toCall(): String? = when {
        typed.isEmpty() -> focused?.number?.ifEmpty { null }
        typed.any { it.isLetter() } -> focused?.number?.ifEmpty { null } ?: dialable(typed)
        else -> typed
    }

    /**
     * The focus kept on [keep] if still shown, else on the first row, or the field when none.
     */
    internal fun refocused(keep: LogEntry?): Dialer {
        val at = keep?.let { k -> shown.indexOfFirst { it.id == k.id } } ?: -1
        return copy(focus = if (at >= 0) at else if (shown.isEmpty()) FIELD else 0)
    }
}

sealed interface DialEvent {
    data class Loaded(val log: List<LogEntry>, val contacts: List<LogEntry> = emptyList()) : DialEvent
    data class Typed(val c: Char) : DialEvent
    data object Erased : DialEvent
    data class Step(val down: Boolean) : DialEvent
    data class Moved(val index: Int) : DialEvent
}

/** Add Pause and Add Wait (guide 7.1 p. 191, B): Android's pause and wait (PhoneNumberUtils), Telecom's post-dial. */
const val PAUSE = ','
const val WAIT = ';'

fun Dialer.reduce(e: DialEvent): Dialer = when (e) {
    // The field keeps the focus until the trackpad or a typed letter leaves it (sim-141: the Phone
    // opens with the cursor in "Find Contact or Dial a Number", no row in blue).
    is DialEvent.Loaded -> copy(log = byNumber(e.log), contacts = e.contacts).let { if (focus == FIELD && typed.isEmpty()) it else it.refocused(focused) }
    // A pause or a wait only after a number (D: the 9900 adds them to a number typed).
    is DialEvent.Typed -> if ((e.c == PAUSE || e.c == WAIT) && typed.isEmpty()) this else copy(typed = typed + e.c).refocused(null)
    DialEvent.Erased -> if (typed.isEmpty()) this else copy(typed = typed.dropLast(1)).refocused(null)
    is DialEvent.Step -> copy(focus = (focus + if (e.down) 1 else -1).coerceIn(FIELD, maxOf(FIELD, shown.size - 1)))
    is DialEvent.Moved -> copy(focus = e.index.coerceIn(FIELD, maxOf(FIELD, shown.size - 1)))
}

private const val DIAL_SYMBOLS = "0123456789+*#"

/** What follows a pause or a wait is dialled after the call connects: the number is what comes before. */
private fun number(typed: String) = typed.takeWhile { it != PAUSE && it != WAIT }

/** A number's key: its last 9 digits ("01 99…" is "+33 1 99…"), as the Hub's; empty without digits. */
fun numberKey(n: String) = n.filter { it.isDigit() }.takeLast(9)

/** The same number however written. */
fun sameNumber(a: String, b: String) = numberKey(a).let { it.isNotEmpty() && it == numberKey(b) }

private fun LogEntry.matches(all: String): Boolean {
    val typed = number(all)
    if (typed.all { it in DIAL_SYMBOLS }) {
        val digits = typed.filter { it.isDigit() }
        if (digits.isEmpty()) return number.contains(typed)
        val own = number.filter { it.isDigit() }
        // "06 12 34 56 78" typed finds "+33 6 12 34 56 78": the national form is a 0 before the last 9 digits
        // (numberKey's rule, D).
        // "+33 6 12 34 56 78" typed finds "06 12 34 56 78": a country code is 1 to 3 digits.
        return number.contains(typed) || own.contains(digits) || "0${numberKey(number)}".contains(digits) ||
            typed.startsWith('+') && (1..3).any { digits.length > it && own.contains("0" + digits.drop(it)) }
    }
    // A name: Contacts' rule, each word typed starts a word of the name.
    return matchesWords(name, typed)
}

/** The keypad digit of each letter of a conventional phone (guide p. 63). */
fun dialable(s: String): String = s.map { c ->
    when (c.lowercaseChar()) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        in 'w'..'z' -> '9'
        else -> c
    }
}.joinToString("")

/**
 * What a key types on the Phone screen: its digit or symbol ([number], the keyboard map's), the
 * 9900's way; with Alt, or on a key without one, its letter (guide p. 63). Null: nothing to type.
 */
fun dialChar(number: Char, letter: Char, alt: Boolean): Char? = when {
    !alt && number in DIAL_SYMBOLS -> number
    letter.isLetter() -> letter
    else -> null
}

/**
 * The images of a log row: cells 0, 1, 2 and 3 of net_rim_Phone-icons42x42.png (A; 1 matches ps
 * 33246, C; the others by eye); [NONE], a contact's row (D).
 */
enum class LogIcon(val cell: Int) { RECEIVED(0), PLACED(1), MISSED_NEW(2), MISSED(3), NONE(-1) }

/**
 * A row's texts: name or number, "Phone: <label>" under a name (sim-141), the date over the time,
 * "08/23" over "6:11p" in the United States (ps 33246), "13/11" over "05:58" in a day-first locale
 * with a 24-hour clock (sim-141).
 */
data class LogRow(val title: String, val subtitle: String, val date: String, val time: String, val icon: LogIcon)

private val MONTH_DAY = DateTimeFormatter.ofPattern("MM/dd", Locale.US)
private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM", Locale.US)
private val TIME_12 = DateTimeFormatter.ofPattern("h:mma", Locale.US)
private val TIME_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** [withheld]: what a call without number shows (the view's string); [dayFirst], [h24]: the phone's date order and clock. */
fun logRow(e: LogEntry, zone: ZoneId, withheld: String = "", dayFirst: Boolean = false, h24: Boolean = false): LogRow {
    val at = Instant.ofEpochMilli(e.date).atZone(zone)
    val number = e.number.ifEmpty { withheld }
    val subtitle = if (e.name.isEmpty()) "" else if (e.label.isEmpty()) number else "${e.label}: $number"
    val icon = when (e.type) {
        LogType.INCOMING -> LogIcon.RECEIVED
        LogType.OUTGOING -> LogIcon.PLACED
        LogType.MISSED -> if (e.isNew) LogIcon.MISSED_NEW else LogIcon.MISSED
        // A contact found by name: no call, so neither icon nor date (D).
        LogType.CONTACT -> return LogRow(e.name, subtitle, "", "", LogIcon.NONE)
    }
    // "6:11p": the 9900's short meridiem (ps 33246, C).
    val time = if (h24) TIME_24.format(at) else TIME_12.format(at).dropLast(1).lowercase()
    return LogRow(e.name.ifEmpty { number }, subtitle, (if (dayFirst) DAY_MONTH else MONTH_DAY).format(at), time, icon)
}

/**
 * The Phone screen's full menu (sim-142, sim-143, C): Call From Contacts under the focus, Call Voice
 * Mail | View | Add Pause, Add Wait | Phone Ringtones, Status, under Switch Application.
 * Options opens the Options list of the launcher (D: the 9900's phone options, no capture, lead mostly to Android's
 * pages here); Help is not there (no help app). Something to call puts "Call …" first and under
 * the focus, with Add Speed Dial for a call of the log (D: no capture of that menu).
 */
enum class PhoneAct {
    CALL, ADD_SPEED_DIAL, DELETE, CALL_FROM_CONTACTS, VOICE_MAIL, CONTACT, TEXT, VIEW, SPEED_DIAL_LIST, COPY,
    ADD_PAUSE, ADD_WAIT, RINGTONES, STATUS, OPTIONS, HELP, CLOSE, SWITCH,
}

fun Dialer.menu(): Menu<PhoneAct> {
    val log = focused?.takeIf { typed.isEmpty() && it.type != LogType.CONTACT }
    if (log != null) return logMenu(log)
    val calls = toCall() != null
    val entries = buildList {
        if (calls) add(MenuEntry(PhoneAct.CALL))
        if (calls && typed.isEmpty()) add(MenuEntry(PhoneAct.ADD_SPEED_DIAL))
        add(MenuEntry(PhoneAct.CALL_FROM_CONTACTS, separatorAbove = calls))
        add(MenuEntry(PhoneAct.VOICE_MAIL))
        add(MenuEntry(PhoneAct.VIEW, separatorAbove = true, submenu = true))
        add(MenuEntry(PhoneAct.ADD_PAUSE, separatorAbove = true))
        add(MenuEntry(PhoneAct.ADD_WAIT))
        add(MenuEntry(PhoneAct.RINGTONES, separatorAbove = true))
        add(MenuEntry(PhoneAct.STATUS))
        add(MenuEntry(PhoneAct.OPTIONS, separatorAbove = true))
        // Close under Help (sim-143's arrow; sim-394, C); Help left out, Bold7 has no help.
        add(MenuEntry(PhoneAct.CLOSE))
    }
    return Menu(entries, highlighted = 0).withHelp(PhoneAct.HELP, before = PhoneAct.CLOSE).withSwitchApplication(PhoneAct.SWITCH)
}

/**
 * A call focused, nothing typed (sim-392 to sim-394, sim-402 to sim-404, simulator, C): "Contact …" and
 * its submenu first in focus, Delete, "Copy …" for a number no contact has. Not there: View's
 * History and Call Notes, Add to Contacts (Add to Existing Contact needs Contacts to complete a
 * contact), Help.
 */
private fun logMenu(e: LogEntry): Menu<PhoneAct> {
    val number = e.number.isNotEmpty()
    val entries = listOfNotNull(
        MenuEntry(PhoneAct.CALL_FROM_CONTACTS, separatorAbove = true),
        MenuEntry(PhoneAct.VOICE_MAIL),
        MenuEntry(PhoneAct.CONTACT, separatorAbove = true, submenu = true).takeIf { number },
        MenuEntry(PhoneAct.VIEW, separatorAbove = !number, submenu = true),
        MenuEntry(PhoneAct.ADD_SPEED_DIAL, separatorAbove = true).takeIf { number },
        MenuEntry(PhoneAct.DELETE, separatorAbove = !number),
        MenuEntry(PhoneAct.COPY, separatorAbove = true).takeIf { number && e.name.isEmpty() },
        MenuEntry(PhoneAct.RINGTONES, separatorAbove = true),
        MenuEntry(PhoneAct.STATUS),
        MenuEntry(PhoneAct.OPTIONS, separatorAbove = true),
        MenuEntry(PhoneAct.CLOSE),
    )
    val focus = entries.indexOfFirst { it.action == PhoneAct.CONTACT }.takeIf { it >= 0 } ?: 0
    return Menu(entries, highlighted = focus).withHelp(PhoneAct.HELP, before = PhoneAct.CLOSE).withSwitchApplication(PhoneAct.SWITCH)
}

/** "Contact …"'s submenu (sim-395, sim-403, C): Call and Text, Call in focus. */
fun contactMenu(): Menu<PhoneAct> = Menu(listOf(MenuEntry(PhoneAct.CALL), MenuEntry(PhoneAct.TEXT)), highlighted = 0)

/**
 * One row per number, its latest call (sim-399, C: a new call from a number takes its row to the top);
 * [log] newest first. Calls without a number stay apart.
 */
fun byNumber(log: List<LogEntry>): List<LogEntry> {
    val seen = HashSet<String>()
    return log.filter { e -> numberKey(e.number).let { it.isEmpty() || seen.add(it) } }
}

/** What a key held on the Phone screen does (guide p. 64): 1 the voice mail, a letter its speed dial, or offers to assign it. */
sealed interface Held {
    data object VoiceMail : Held
    data class Call(val number: String) : Held
    data class Assign(val key: Char) : Held
}

/** The key whose letter is [letter], held after [before] was typed: only the first key of a number is a shortcut; null, it types. */
fun held(before: String, letter: Char, speed: SpeedDial): Held? {
    val key = letter.lowercaseChar()
    return when {
        before.isNotEmpty() || key !in 'a'..'z' -> null
        key == VOICE_MAIL_KEY -> Held.VoiceMail
        else -> speed[key]?.let { Held.Call(it.number) } ?: Held.Assign(key)
    }
}
