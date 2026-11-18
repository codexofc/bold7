package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.DEFAULT_DISABLED_SOURCES
import dev.bold7.hub.data.HubAccounts
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.fromSourceIn
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
    /** Inbox Management's other apps (D): shown in their own view only, as a hidden account. */
    val hiddenApps: Set<String> = emptySet(),
    /**
     * Message Sources (D, no such screen on the 9900): the sources that no longer enter the Hub, an
     * account's id or an app's package. The Hub reads them here (SourcesProvider) and forgets
     * their items; the list leaves them out at once.
     */
    val disabledSources: Set<String> = DEFAULT_DISABLED_SOURCES,
)

/** A source of [sourceIds] that is an app: account ids ("sms", "calls", "mail-…") never hold a dot, packages always do. */
fun isAppSource(id: String) = '.' in id

/** Message Sources' rows, in order: Text Messages, each mail account of [mail], Calls, Calendar, then each app of [apps]. */
fun sourceIds(mail: List<String>, apps: List<String>): List<String> =
    listOf(HubAccounts.TEXT_MESSAGES.id) + mail + listOf(HubAccounts.CALLS.id, HubAccounts.CALENDAR.id) + apps

/** Message Sources' check boxes, ticked: the source enters the Hub. */
fun MessagesOptions.sourceChecks(mail: List<String>, apps: List<String>): List<Boolean> = sourceIds(mail, apps).map { it !in disabledSources }

/** What a search looks at: everything (the search bar), or one field of the message Search by was opened on (guide p. 86, B). */
enum class SearchIn { ANY, SENDER, RECIPIENT, SUBJECT }

/** The saved messages are kept by this app, by this key (Hub items are never rewritten for it). */
fun HubItem.savedKey() = "$accountId|$sourceKey"

/** Whether [item] stays in [filter]'s list under these options, within [account]'s own view when there is one. */
fun MessagesOptions.keeps(item: HubItem, filter: Filter, account: String?, smsApp: String?, app: String? = null): Boolean = when {
    item.fromSourceIn(disabledSources) -> false
    item.kind == ItemKind.NOTIFICATION && item.folder in hiddenApps && app != item.folder -> false
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
enum class OptionsScreen { LIST, DISPLAY, INBOX, SOURCES }

/** Message Display and Actions' check boxes (guide 7.1 p. 87-89, B), in order, with their value. */
fun MessagesOptions.displayChecks(): List<Pair<Int, Boolean>> = listOf(0 to hideSent, 1 to confirmDelete, 2 to confirmMarkPriorRead)

/**
 * Inbox Management's check boxes (guide p. 87, B): one per mail account of [accounts] (ticked:
 * shown), then Text Messages.
 */
fun MessagesOptions.inboxChecks(accounts: List<String>, apps: List<String> = emptyList()): List<Boolean> =
    accounts.map { it !in hiddenAccounts } + showTexts + apps.map { it !in hiddenApps }

/** The option behind check box [index] of [screen] flipped; [accounts] are Inbox Management's mail accounts. */
fun MessagesOptions.toggled(screen: OptionsScreen, index: Int, accounts: List<String>, apps: List<String> = emptyList()): MessagesOptions = when (screen) {
    OptionsScreen.DISPLAY -> when (index) {
        0 -> copy(hideSent = !hideSent)
        1 -> copy(confirmDelete = !confirmDelete)
        2 -> copy(confirmMarkPriorRead = !confirmMarkPriorRead)
        else -> this
    }
    OptionsScreen.INBOX -> when {
        index in accounts.indices -> accounts[index].let { copy(hiddenAccounts = if (it in hiddenAccounts) hiddenAccounts - it else hiddenAccounts + it) }
        index == accounts.size -> copy(showTexts = !showTexts)
        else -> apps.getOrNull(index - accounts.size - 1)?.let { copy(hiddenApps = hiddenApps.flipped(it)) } ?: this
    }
    OptionsScreen.SOURCES -> sourceIds(accounts, apps).getOrNull(index)?.let { copy(disabledSources = disabledSources.flipped(it)) } ?: this
    OptionsScreen.LIST -> this
}

private fun Set<String>.flipped(id: String) = if (id in this) this - id else this + id
