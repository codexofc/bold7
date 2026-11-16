package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.isText

// The options of Messages (Menu > Options, BBOS 7.1: Message Display and Actions, Inbox
// Management; the Display Style menu), pure. Only those that do something in Bold7 are here
// Never one of them deletes a message: they only choose what
// the list shows and what is asked first.

/**
 * [hideSent]: Hide Sent Messages (guide 7.1 p. 87, B). [confirmDelete], [confirmMarkPriorRead]:
 * the Actions section (p. 89, B), on by default. [showTexts] and [hiddenAccounts]: Inbox
 * Management (p. 87, B), the "Text Messages" check box and the mail accounts' (a hidden account
 * shows from its own icon only, a text still shows in Text Messages). [group]: Display Style,
 * Group by Subject (a conversation or a mail thread is one row, p. 88, B) or Single Messages.
 */
data class MessagesOptions(
    val hideSent: Boolean = false,
    val confirmDelete: Boolean = true,
    val confirmMarkPriorRead: Boolean = true,
    val showTexts: Boolean = true,
    val hiddenAccounts: Set<String> = emptySet(),
    val group: Boolean = true,
)

/** What a search looks at: everything (the search bar), or one field of the message Search by was opened on (guide p. 86, B). */
enum class SearchIn { ANY, SENDER, RECIPIENT, SUBJECT }

/** The saved messages are kept by this app, by this key (Hub items are never rewritten for it). */
fun HubItem.savedKey() = "$accountId|$sourceKey"

/** Whether [item] stays in [filter]'s list under these options, within [account]'s own view when there is one. */
fun MessagesOptions.keeps(item: HubItem, filter: Filter, account: String?, smsApp: String?): Boolean = when {
    hideSent && item.box == Box.SENT && filter != Filter.OUTGOING -> false
    item.accountId in hiddenAccounts && account != item.accountId -> false
    !showTexts && filter != Filter.TEXT && item.isText(smsApp) -> false
    else -> true
}

/** A message Save offers itself on: a mail or a text (the guide says mail and PIN, D: no PIN here). */
fun HubItem.canSave() = kind == ItemKind.EMAIL || kind == ItemKind.SMS || kind == ItemKind.MMS

/** [query] in the one [field] of [this], on the box that field belongs to; [SearchIn.ANY] is the search bar's. */
fun HubItem.matches(query: String, field: SearchIn): Boolean = when (field) {
    SearchIn.ANY -> matches(query)
    SearchIn.SENDER -> box == Box.INBOX && (sender.contains(query, ignoreCase = true) || address?.contains(query, ignoreCase = true) == true)
    SearchIn.RECIPIENT -> box != Box.INBOX && (sender.contains(query, ignoreCase = true) || address?.contains(query, ignoreCase = true) == true)
    SearchIn.SUBJECT -> subject?.contains(query, ignoreCase = true) == true
}

/** The screens of Messages Options: its list (sim-294, sim-295, C) and the forms of the rows that have an effect here. */
enum class OptionsScreen { LIST, DISPLAY, INBOX }

/** Message Display and Actions' check boxes (guide 7.1 p. 87-89, B), in order, with their value. */
fun MessagesOptions.displayChecks(): List<Pair<Int, Boolean>> = listOf(0 to hideSent, 1 to confirmDelete, 2 to confirmMarkPriorRead)

/**
 * Inbox Management's check boxes (guide p. 87, B): one per mail account of [accounts] (ticked:
 * shown), then Text Messages.
 */
fun MessagesOptions.inboxChecks(accounts: List<String>): List<Boolean> = accounts.map { it !in hiddenAccounts } + showTexts

/** The option behind check box [index] of [screen] flipped; [accounts] are Inbox Management's mail accounts. */
fun MessagesOptions.toggled(screen: OptionsScreen, index: Int, accounts: List<String>): MessagesOptions = when (screen) {
    OptionsScreen.DISPLAY -> when (index) {
        0 -> copy(hideSent = !hideSent)
        1 -> copy(confirmDelete = !confirmDelete)
        2 -> copy(confirmMarkPriorRead = !confirmMarkPriorRead)
        else -> this
    }
    OptionsScreen.INBOX -> when {
        index in accounts.indices -> accounts[index].let { copy(hiddenAccounts = if (it in hiddenAccounts) hiddenAccounts - it else hiddenAccounts + it) }
        index == accounts.size -> copy(showTexts = !showTexts)
        else -> this
    }
    OptionsScreen.LIST -> this
}
