package dev.bold7.messages

import dev.bold7.hub.data.numberKey
import dev.bold7.ui.matchesWords

// To of a new message: "type a contact name", "Menu > Choose Contact" or "type a
// phone number" (guide 7.1 p. 103, B), a To line per recipient and an empty one under them
// ("Batman (Mobile)", gsm 025, C). Pure; AndroidContactBook reads the contacts.

/** One of a contact's numbers, its type as OS 7 names it ("Mobile"). */
data class PhoneNumber(val label: String, val number: String)

/** A contact and its numbers, as To proposes it while a name is typed. */
data class Contact(val name: String, val numbers: List<PhoneNumber>)

/** A recipient in To: a contact's number, or a number typed ([name] null). */
data class Recipient(val number: String, val name: String? = null, val label: String? = null) {
    /** What its To line and the thread's band show: "Batman (Mobile)" (gsm 025, gsm 023, C), or the number. */
    val shown: String get() = when {
        name == null -> number
        label == null -> name
        else -> "$name ($label)"
    }
}

fun Contact.recipient(n: PhoneNumber) = Recipient(n.number, name, n.label)

private const val DIALLING_MARKS = "+-() ./"

/** [typed] trimmed when it is a phone number (digits and dialling marks), else null: a name. */
fun dialable(typed: String): String? {
    val t = typed.trim()
    return t.takeIf { s -> s.any { it.isDigit() } && s.all { it.isDigit() || it in DIALLING_MARKS } }
}

/** The contacts To proposes for [typed]: a name's, by the words of Contacts; none for a number. */
fun suggestions(contacts: List<Contact>, typed: String): List<Contact> =
    if (typed.isBlank() || typed.none { it.isLetter() }) emptyList() else contacts.filter { matchesWords(it.name, typed) }

/** A contact chosen in To: its only number at once; several, the user picks one (D, as Select Contact). */
sealed interface ToChoice {
    data class One(val recipient: Recipient) : ToChoice
    data class Numbers(val contact: Contact) : ToChoice
}

fun choice(c: Contact): ToChoice = c.numbers.singleOrNull()?.let { ToChoice.One(c.recipient(it)) } ?: ToChoice.Numbers(c)

/** The same number however written: the Hub's key, its last 9 digits ("01 99…" is "+33 1 99…"). */
fun sameNumber(a: String, b: String) = numberKey(a) == numberKey(b)

/**
 * The one-to-one conversation of [number] among Telephony's [threads] (newest first, each with its
 * members), so that Text from Contacts shows its history at once; a group is never one. Null: none.
 */
fun threadFor(number: String, threads: List<Pair<Long, List<String>>>): Long? =
    threads.firstOrNull { (_, members) -> members.size == 1 && sameNumber(members[0], number) }?.first

/** Android's Phone.TYPE as OS 7's list names it (sim-192, C), Other for the rest (D, as Contacts). */
fun phoneLabel(type: Int): String = when (type) {
    // TYPE_MOBILE, TYPE_WORK_MOBILE
    2, 17 -> "Mobile"
    1 -> "Home"
    // TYPE_WORK, TYPE_COMPANY_MAIN
    3, 10 -> "Work"
    // TYPE_PAGER, TYPE_WORK_PAGER
    6, 18 -> "Pager"
    // TYPE_FAX_WORK, TYPE_FAX_HOME, TYPE_OTHER_FAX
    4, 5, 13 -> "Work Fax"
    else -> "Other"
}
