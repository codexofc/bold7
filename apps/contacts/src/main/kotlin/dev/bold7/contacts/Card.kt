package dev.bold7.contacts

import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withSwitchApplication

// A contact's card of BBOS 7.1, pure (gsm 015, C; docs/BBOS7-VISUEL.md §30): the name and picture,
// then boxes of details, Phone Numbers then Email Addresses (C), Addresses (D), then Recent Activity (sim-194, drawn by CardView); the
// trackpad walks the details, the menu acts on the one under it.

enum class Kind { PHONE, EMAIL, ADDRESS }

/** A number, an email address or a postal address: its label ("Mobile"), its value, its provider row. */
data class Detail(val kind: Kind, val label: String, val value: String, val dataId: Long)

data class Card(
    val id: Long,
    val name: String,
    val company: String,
    val details: List<Detail>,
    val photo: String? = null,
) {
    /** The boxes of the card, each with its details, empty ones left out. */
    fun sections(): List<Pair<Section, List<Detail>>> =
        Section.entries.mapNotNull { s -> details.filter { it.kind == s.kind }.takeIf { it.isNotEmpty() }?.let { s to it } }

    /** The details in the order they are shown. */
    val ordered: List<Detail> get() = sections().flatMap { it.second }
}

enum class Section(val kind: Kind) { PHONES(Kind.PHONE), EMAILS(Kind.EMAIL), ADDRESSES(Kind.ADDRESS) }

enum class CardAct { CALL, TEXT, EMAIL, COPY, EDIT, DELETE, SWITCH, SEND, SEND_EMAIL, SEND_TEXT, HELP, CLOSE }

/** The card on the screen and the detail under the trackpad (its index in [Card.ordered]). */
data class CardScreen(val card: Card, val focus: Int = 0) {
    val focused: Detail? get() = card.ordered.getOrNull(focus)

    fun step(down: Boolean): CardScreen {
        val last = card.ordered.size - 1
        return if (last < 0) this else copy(focus = (focus + if (down) 1 else -1).coerceIn(0, last))
    }

    fun moved(detail: Detail): CardScreen = card.ordered.indexOf(detail).let { if (it < 0) this else copy(focus = it) }

    /** The card read again: the focus stays on the same provider row, else at its place. */
    fun loaded(next: Card): CardScreen {
        val id = focused?.dataId
        val at = next.ordered.indexOfFirst { it.dataId == id }
        return CardScreen(next, if (at >= 0) at else focus.coerceIn(0, (next.ordered.size - 1).coerceAtLeast(0)))
    }

    /** The number Call and Text use: the focused one, else the first (D). */
    val number: Detail? get() = focused?.takeIf { it.kind == Kind.PHONE } ?: card.ordered.firstOrNull { it.kind == Kind.PHONE }

    /** The address Email uses: the focused one, else the first (D). */
    val email: Detail? get() = focused?.takeIf { it.kind == Kind.EMAIL } ?: card.ordered.firstOrNull { it.kind == Kind.EMAIL }

    /**
     * Menu key (sim-195, sim-196, C), what Bold7 does of it: Switch Application; Copy when a detail has the focus; Edit,
     * Delete, Send Contact Card ▸; Call when the contact has a number; Email, Text when it has an
     * address, a number; Help, Close. Not there: Select Text, Activity Log, Add to Home Screen, Add
     * Speed Dial (D).
     * Focused: Call on a number, Email on an address, Edit otherwise (sim-182b, C).
     */
    fun menu(): Menu<CardAct> {
        val d = focused
        val entries = listOfNotNull(
            d?.let { MenuEntry(CardAct.COPY) },
            MenuEntry(CardAct.EDIT, separatorAbove = d != null),
            MenuEntry(CardAct.DELETE),
            MenuEntry(CardAct.SEND, submenu = true),
            number?.let { MenuEntry(CardAct.CALL, separatorAbove = true) },
            email?.let { MenuEntry(CardAct.EMAIL, separatorAbove = true) },
            number?.let { MenuEntry(CardAct.TEXT, separatorAbove = email == null) },
            MenuEntry(CardAct.HELP, separatorAbove = true),
            MenuEntry(CardAct.CLOSE),
        )
        val default = when (d?.kind) {
            Kind.PHONE -> CardAct.CALL
            Kind.EMAIL -> CardAct.EMAIL
            else -> CardAct.EDIT
        }
        return Menu(entries, highlighted = entries.indexOfFirst { it.action == default }).withSwitchApplication(CardAct.SWITCH)
    }

    /**
     * The short menu, the focused detail's actions and the default one last, by Full Menu: on a
     * number Copy, Edit, Text, Call (sim-197, C); on an address Copy, Edit, Email; otherwise Copy or
     * Delete, then Edit (D).
     */
    fun shortMenu(): Menu<CardAct> {
        val acts = when (focused?.kind) {
            Kind.PHONE -> listOf(CardAct.COPY, CardAct.EDIT, CardAct.TEXT, CardAct.CALL)
            Kind.EMAIL -> listOf(CardAct.COPY, CardAct.EDIT, CardAct.EMAIL)
            Kind.ADDRESS -> listOf(CardAct.COPY, CardAct.DELETE, CardAct.EDIT)
            null -> listOf(CardAct.DELETE, CardAct.EDIT)
        }
        return Menu(acts.map { MenuEntry(it) }, line = focus, highlighted = acts.size - 1)
    }
}

/** The submenu of Send Contact Card on the card, as the list's. */
fun sendCardActions(): Menu<CardAct> = Menu(listOf(MenuEntry(CardAct.SEND_EMAIL), MenuEntry(CardAct.SEND_TEXT)), highlighted = 0)
