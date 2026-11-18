package dev.bold7.contacts

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.matchesWords

// The contact list of BBOS 7.1 and its Select Contact screen, pure (docs/BBOS7-VISUEL.md §30): "New Contact" first, the contacts in the provider's order, the focus on
// the first of them (sim-178, sim-183, C); typing filters them (guide p. 192, "type all or part of a
// contact's name", B; sim-189, sim-190, C).

/** A contact as the list shows it: its name, and the first number and address its menu offers. */
data class Person(val id: Long, val name: String, val phone: String?, val email: String?, val photo: String? = null)

sealed interface Row
data object NewContact : Row
data class Entry(val person: Person) : Row

enum class ListAct { NEW, VIEW, EDIT, DELETE, CALL, TEXT, EMAIL, SELECT, SWITCH, SEND, SEND_EMAIL, SEND_TEXT, HELP, CLOSE }

sealed interface ListEvent {
    data class Loaded(val people: List<Person>) : ListEvent
    data class Typed(val char: Char) : ListEvent
    data object Erased : ListEvent
    data class Step(val down: Boolean) : ListEvent
    /** A touch on the row at [row]. */
    data class Moved(val row: Int) : ListEvent
    /** A new contact saved, [id]: focused now, or at the next load if the list has not read it yet (sim-193, C). */
    data class Created(val id: Long) : ListEvent
}

data class ContactList(
    val all: List<Person> = emptyList(),
    val typed: String = "",
    /** An index into [rows]. */
    val focus: Int = 0,
    /** Select Contact (another app's pick): the same rows (sim-183, C), its click chooses. */
    val picking: Boolean = false,
    val loaded: Boolean = false,
    /** A contact just created ([ListEvent.Created]) that the list has not read yet. */
    val wanted: Long? = null,
) {
    val shown: List<Person> = filter(all, typed)

    val rows: List<Row> = listOf(NewContact) + shown.map(::Entry)

    val focused: Row? get() = rows.getOrNull(focus)

    /** Something typed that no contact matches (the list then shows only New Contact). */
    val noMatch: Boolean get() = typed.isNotBlank() && shown.isEmpty()

    fun reduce(e: ListEvent): ContactList = when (e) {
        is ListEvent.Loaded -> {
            val id = (focused as? Entry)?.person?.id
            val next = copy(all = e.people, loaded = true)
            val at = next.rows.indexOfFirst { it is Entry && it.person.id == id }
            val kept = next.copy(focus = when {
                at >= 0 -> at
                // First loaded: the first contact (sim-178, C).
                !loaded -> next.firstMatch()
                else -> focus.coerceIn(0, next.rows.size - 1)
            })
            (wanted?.let(kept::focusedOn) ?: kept).copy(wanted = null)
        }
        is ListEvent.Created -> focusedOn(e.id) ?: copy(wanted = e.id)
        is ListEvent.Typed -> retyped(typed + e.char)
        ListEvent.Erased -> if (typed.isEmpty()) this else retyped(typed.dropLast(1))
        is ListEvent.Step -> copy(focus = (focus + if (e.down) 1 else -1).coerceIn(0, rows.size - 1))
        is ListEvent.Moved -> copy(focus = e.row.coerceIn(0, rows.size - 1))
    }

    /** Escape: what is typed goes first (as the Phone's field, sim-144, C); null leaves the screen. */
    fun back(): ContactList? = if (typed.isEmpty()) null else retyped("")

    private fun focusedOn(id: Long): ContactList? =
        rows.indexOfFirst { it is Entry && it.person.id == id }.takeIf { it >= 0 }?.let { copy(focus = it) }

    /** The first contact's row; New Contact's when none is shown (sim-190, C). */
    private fun firstMatch() = rows.indexOfFirst { it is Entry }.coerceAtLeast(0)

    /** The focus goes to the first match (sim-189, C). */
    private fun retyped(text: String): ContactList = copy(typed = text).let { it.copy(focus = it.firstMatch()) }

    /** What the field shows: the first letter as a capital (sim-189: "T", C). */
    val field: String get() = typed.replaceFirstChar { it.uppercaseChar() }

    /**
     * The full menu (Menu key, sim-179, sim-180, sim-198, C), what Bold7 does of it: Switch Application, the focused
     * contact's View (focused), Edit, Delete; New Contact; then Email, Call, Text when it has an
     * address or a number, in that order; Send Contact Card ▸ under Delete, Help and Close last (sim-179,
     * sim-180). Not there, with no effect to give them: Switch Input Language, Add to Home Screen, New
     * Group, Filter, SIM Phone Book, Options (D). On New Contact, New
     * Contact alone before Help and Close (D). Select Contact has the same menu (sim-183, C), Select
     * first and focused (D).
     */
    fun menu(): Menu<ListAct> = baseMenu().withSwitchApplication(ListAct.SWITCH)

    private fun baseMenu(): Menu<ListAct> {
        val end = listOf(MenuEntry(ListAct.HELP, separatorAbove = true), MenuEntry(ListAct.CLOSE))
        val p = (focused as? Entry)?.person ?: return Menu(listOf(MenuEntry(ListAct.NEW)) + end, highlighted = 0)
        val reach = reach(p)
        val entries = listOfNotNull(MenuEntry(ListAct.SELECT).takeIf { picking }) +
            listOf(MenuEntry(ListAct.VIEW, separatorAbove = picking), MenuEntry(ListAct.EDIT), MenuEntry(ListAct.DELETE), MenuEntry(ListAct.SEND, submenu = true), MenuEntry(ListAct.NEW, separatorAbove = true)) +
            reach.mapIndexed { i, a -> MenuEntry(a, separatorAbove = i == 0) } + end
        return Menu(entries, highlighted = 0)
    }

    /**
     * The short menu (the trackpad held or a long press) on a contact: Delete, Edit, then its
     * reaching actions, and View last, focused, by Full Menu (sim-181, C; the reaching ones and
     * their order D). Selecting: Select last.
     */
    fun shortMenu(): Menu<ListAct>? {
        val p = (focused as? Entry)?.person ?: return null
        val acts = listOf(ListAct.DELETE, ListAct.EDIT) + reach(p) + (if (picking) listOf(ListAct.VIEW, ListAct.SELECT) else listOf(ListAct.VIEW))
        return Menu(acts.map { MenuEntry(it) }, line = focus, highlighted = acts.size - 1)
    }

    private fun reach(p: Person) = listOfNotNull(p.email?.let { ListAct.EMAIL }, p.phone?.let { ListAct.CALL }, p.phone?.let { ListAct.TEXT })
}

/** The contacts whose name has, for each word typed, a word starting with it; case and accents ignored. */
fun filter(people: List<Person>, typed: String): List<Person> = people.filter { matchesWords(it.name, typed) }

/** The submenu of Send Contact Card (D: the guide names the email and the text message as what takes a card, p. 111). */
fun sendCardMenu(): Menu<ListAct> = Menu(listOf(MenuEntry(ListAct.SEND_EMAIL), MenuEntry(ListAct.SEND_TEXT)), highlighted = 0)
