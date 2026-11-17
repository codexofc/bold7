package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.MailResult
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.TextAct
import dev.bold7.ui.matchesWords
import dev.bold7.ui.withSwitchApplication
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

// The mail of Messages, pure: addresses typed in To and Cc, Reply, Reply To All and
// Forward (guide 7.1 p. 51, 93, B), the drafts that keep what was written, the setup of an
// account. MailActivity, ComposeMailActivity and MailAccountsActivity render these; apps/hub sends.

/** A mail address and its name, as To, Cc and the reading screen show it. */
data class Addr(val email: String, val name: String? = null) {
    val shown: String get() = name ?: email
}

private val EMAIL = Regex("[^@\\s<>\",;]+@[^@\\s<>\",;]+\\.[^@\\s<>\",;]+")
private val NAMED = Regex("^\\s*\"?(.*?)\"?\\s*<([^<>]+)>\\s*$")

/** "Name <a@b.c>", quoted when the name holds a comma or a semicolon; the address alone without a name. */
fun formatAddress(a: Addr): String = when {
    a.name.isNullOrBlank() -> a.email
    a.name.any { it == ',' || it == ';' || it == '"' } -> "\"${a.name.replace("\"", "")}\" <${a.email}>"
    else -> "${a.name} <${a.email}>"
}

fun formatAddresses(list: List<Addr>): String = list.joinToString(", ", transform = ::formatAddress)

/** What To or Cc holds, split on commas and semicolons outside quotes. */
private fun tokens(text: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    for (c in text) {
        when {
            c == '"' -> {
                quoted = !quoted
                current.append(c)
            }
            (c == ',' || c == ';') && !quoted -> out += current.toString().also { current.clear() }
            else -> current.append(c)
        }
    }
    out += current.toString()
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}

/** The addresses typed in a field, or null when one of them is not an address (a name not chosen). */
fun parseAddresses(text: String): List<Addr>? = tokens(text).map { t ->
    val named = NAMED.find(t)
    val (email, name) = if (named != null) named.groupValues[2].trim() to named.groupValues[1].trim().takeIf { it.isNotEmpty() } else t to null
    if (!email.matches(EMAIL)) return null
    Addr(email, name)
}

/** A mail as Messages opened it, from apps/hub ([own]: the account's address). */
data class Opened(
    val from: Addr?,
    val to: List<Addr>,
    val cc: List<Addr>,
    val replyTo: List<Addr>,
    val subject: String,
    val date: Long,
    val messageId: String?,
    val references: List<String>,
    val text: String,
    val own: String,
)

/**
 * A mail being written, or kept as a draft ([id], once saved): To and Cc as typed, the account it
 * leaves from, and for a reply the message it answers.
 */
data class MailDraft(
    val id: String? = null,
    val account: String?,
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val text: String = "",
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val savedAt: Long = 0,
) {
    /** An account, every recipient an address, and someone to send to. */
    val sendable: Boolean get() {
        val t = parseAddresses(to) ?: return false
        val c = parseAddresses(cc) ?: return false
        return account != null && (t.isNotEmpty() || c.isNotEmpty())
    }

    /** Nothing written in it. */
    val empty: Boolean get() = to.isBlank() && cc.isBlank() && subject.isBlank() && text.isBlank()

    /** The same writing, whatever its id and save time: what "Changes made!" compares (sim-47, C). */
    fun sameAs(other: MailDraft) = copy(id = null, savedAt = 0) == other.copy(id = null, savedAt = 0)
}

/** [subject] with [prefix] ("Re: ", "Fw: ") once, whatever case the one it had (D for the case). */
fun subjectWith(prefix: String, subject: String): String {
    val bare = prefix.trim().trimEnd(':')
    val rest = Regex("^\\s*(${Regex.escape(bare)}|${if (bare == "Fw") "Fwd" else bare})\\s*:\\s*", RegexOption.IGNORE_CASE).replace(subject, "")
    return prefix + rest
}

/** What starts the original under a reply or a forward ([quoted]). */
const val ORIGINAL_MESSAGE = "-----Original Message-----"

/**
 * [text] as it is sent with Use Auto Signature (guide 7.1 p. 90, B: "added to your email after you
 * send it", never in Compose): [signature] on the line under what I wrote, above the original of a
 * reply or a forward (S); unchanged without one. The draft keeps the text unsigned.
 */
fun signed(text: String, signature: String?): String {
    if (signature.isNullOrBlank()) return text
    val at = Regex("(?m)^" + Regex.escape(ORIGINAL_MESSAGE)).find(text)?.range?.first ?: text.length
    val mine = text.substring(0, at).trimEnd()
    val original = text.substring(at)
    val head = if (mine.isEmpty()) signature else "$mine\n$signature"
    return if (original.isEmpty()) head else "$head\n\n$original"
}

/**
 * The original under a reply or a forward: "-----Original Message-----" and its From, Sent, To,
 * Cc and Subject, then its text (S: the 9900's form as remembered; border_email_original_message_header,
 * A, frames it on the 9900, here plain text).
 */
fun quoted(m: Opened, date: (Long) -> String): String = buildString {
    append("\n\n").append(ORIGINAL_MESSAGE).append('\n')
    m.from?.let { append("From: ").append(formatAddress(it)).append('\n') }
    append("Sent: ").append(date(m.date)).append('\n')
    if (m.to.isNotEmpty()) append("To: ").append(formatAddresses(m.to)).append('\n')
    if (m.cc.isNotEmpty()) append("Cc: ").append(formatAddresses(m.cc)).append('\n')
    append("Subject: ").append(m.subject).append("\n\n")
    append(m.text)
}

private fun List<Addr>.without(emails: Collection<String>) = filter { a -> emails.none { it.equals(a.email, ignoreCase = true) } }.distinctBy { it.email.lowercase() }

/**
 * Reply (R) to the sender, its Reply-To first; Reply To All (L) also to the others of To and Cc,
 * never to the account itself (guide 7.1 p. 51, 93, B). A reply to a mail of mine goes to its
 * recipients. Threaded: In-Reply-To, References.
 */
fun reply(m: Opened, account: String, all: Boolean, date: (Long) -> String): MailDraft {
    val mine = m.from?.email.equals(m.own, ignoreCase = true)
    val to = when {
        mine -> m.to.without(listOf(m.own))
        m.replyTo.isNotEmpty() -> m.replyTo
        else -> listOfNotNull(m.from)
    }
    val cc = if (all) (m.to + m.cc).without(to.map { it.email } + m.own) else emptyList()
    return MailDraft(
        account = account, to = formatAddresses(to), cc = formatAddresses(cc), subject = subjectWith("Re: ", m.subject),
        text = quoted(m, date), inReplyTo = m.messageId, references = m.references + listOfNotNull(m.messageId),
    )
}

/** Forward (F): no recipient yet, the original below (guide 7.1 p. 51, 93, B). */
fun forward(m: Opened, account: String, date: (Long) -> String) =
    MailDraft(account = account, subject = subjectWith("Fw: ", m.subject), text = quoted(m, date))

/** The inbox's account of the drafts kept on the phone: never in the Hub's store. */
const val DRAFTS_ACCOUNT = "drafts"

/** A draft as a row of the inbox, with the pen of a draft (Inbox.icon) and its first recipient. */
fun MailDraft.asItem(): HubItem {
    val first = tokens(to).firstOrNull()?.let { t -> parseAddresses(t)?.firstOrNull()?.shown ?: t }
    return HubItem(
        accountId = DRAFTS_ACCOUNT, sourceKey = id.orEmpty(), kind = ItemKind.EMAIL, timestamp = savedAt,
        sender = first.orEmpty(), subject = subject, preview = text, box = Box.DRAFT, read = true, sourceRead = true,
    )
}

/** The draft as kept in its file (java.util.Properties: every character escaped). */
fun MailDraft.encoded(): String {
    val p = Properties()
    p["v"] = "1"
    id?.let { p["id"] = it }
    account?.let { p["account"] = it }
    p["to"] = to
    p["cc"] = cc
    p["subject"] = subject
    p["text"] = text
    inReplyTo?.let { p["inReplyTo"] = it }
    p["references"] = references.joinToString(" ")
    p["savedAt"] = savedAt.toString()
    return StringWriter().also { p.store(it, null) }.toString()
}

/** A kept draft, or null for a file that is not one. */
fun draftOf(encoded: String): MailDraft? {
    val p = Properties().apply { load(StringReader(encoded)) }
    if (p.getProperty("v") != "1") return null
    return MailDraft(
        id = p.getProperty("id"), account = p.getProperty("account"), to = p.getProperty("to").orEmpty(), cc = p.getProperty("cc").orEmpty(),
        subject = p.getProperty("subject").orEmpty(), text = p.getProperty("text").orEmpty(), inReplyTo = p.getProperty("inReplyTo"),
        references = p.getProperty("references").orEmpty().split(' ').filter { it.isNotEmpty() },
        savedAt = p.getProperty("savedAt")?.toLongOrNull() ?: 0,
    )
}

/** A contact and its mail addresses, for To and Cc. */
data class EmailContact(val name: String, val emails: List<String>)

/** What is being typed in To or Cc: the text after its last comma or semicolon. */
private fun typing(text: String): String = text.substringAfterLast(',').substringAfterLast(';').trim()

/**
 * The addresses proposed for the name being typed in To or Cc (the same rule as To: a word of
 * the name starting with each word typed); none while an address is typed (an @ in it).
 */
fun emailSuggestions(contacts: List<EmailContact>, field: String): List<Addr> {
    val t = typing(field)
    if (t.isEmpty() || '@' in t || t.none { it.isLetter() }) return emptyList()
    return contacts.filter { matchesWords(it.name, t) }.flatMap { c -> c.emails.map { Addr(it, c.name) } }
}

/** [field] with what was being typed replaced by [chosen], ready for the next one. */
fun withChosen(field: String, chosen: Addr): String {
    val cut = maxOf(field.lastIndexOf(','), field.lastIndexOf(';'))
    val before = if (cut < 0) "" else field.substring(0, cut + 1).trimEnd() + " "
    return before + formatAddress(chosen) + ", "
}

/** Whether [text] is one mail address, as Email Setup takes it. */
fun isAddress(text: String) = text.trim().matches(EMAIL)

/** A mail account of apps/hub: its id, its address and its signature (null: Use Auto Signature off). */
data class MailAccountInfo(val id: String, val address: String, val signature: String?)

/** The accounts, and the providers whose accounts this build signs in through the browser ("Gmail", "Outlook"). */
data class MailAccounts(val list: List<MailAccountInfo> = emptyList(), val browser: List<String> = emptyList())

/** What the setup of an account says when it fails (D: the 9900's are not captured). */
enum class SetupNotice { INVALID, AUTH, AUTH_GMAIL, AUTH_ICLOUD, CERTIFICATE, NETWORK, REFUSED, SERVERS, UNAVAILABLE, CANCELLED }

/**
 * Email Setup (guide 7.1 p. 90, B: "follow the screens to set up your email address"): the
 * address and the app password; the servers only when apps/hub does not know the domain.
 */
data class SetupState(
    val address: String = "",
    val password: String = "",
    val servers: Boolean = false,
    val imapHost: String = "",
    val imapPort: String = "993",
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val busy: Boolean = false,
    val notice: SetupNotice? = null,
) {
    val ready: Boolean get() = !busy && address.trim().matches(EMAIL) && password.isNotEmpty() &&
        (!servers || (imapHost.isNotBlank() && smtpHost.isNotBlank() && port(imapPort) != null && port(smtpPort) != null))

    fun answered(result: MailResult): SetupState = copy(
        busy = false,
        servers = servers || result == MailResult.SERVERS,
        notice = when (result) {
            MailResult.OK -> null
            MailResult.AUTH -> refusedBy(address)
            MailResult.SERVERS -> SetupNotice.SERVERS
            MailResult.CERTIFICATE -> SetupNotice.CERTIFICATE
            MailResult.NETWORK -> SetupNotice.NETWORK
            MailResult.REFUSED -> SetupNotice.REFUSED
            MailResult.UNAVAILABLE -> SetupNotice.UNAVAILABLE
            MailResult.CANCELLED -> SetupNotice.CANCELLED
        },
    )
}

/**
 * A refused password: Gmail and iCloud only take an app password over IMAP, and the most common
 * mistake is the account's own password, so their notice says where to make one (D).
 */
private fun refusedBy(address: String): SetupNotice = when (address.trim().substringAfterLast('@').lowercase()) {
    "gmail.com", "googlemail.com" -> SetupNotice.AUTH_GMAIL
    "icloud.com", "me.com", "mac.com" -> SetupNotice.AUTH_ICLOUD
    else -> SetupNotice.AUTH
}

fun port(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

/** Compose Email's menu actions; the last four are core/ui's [TextAct], named as it names them. */
enum class ComposeAct { SEND, SAVE_DRAFT, CLOSE, SWITCH, START_SELECTION, CUT, COPY, PASTE }

/**
 * Compose Email's full menu (sim-46, sim-47, C): Send first and focused when the mail
 * can leave, Switch Application after it (as the text thread's, sim-51, C; D here), else first;
 * Save Draft; the focused field's selection entries [text] in Save Draft's group (D, as MemoPad's:
 * before Close); Close alone at the end.
 */
fun composeMenu(sendable: Boolean, text: List<TextAct>): Menu<ComposeAct> {
    val entries = listOfNotNull(
        MenuEntry(ComposeAct.SEND).takeIf { sendable },
        MenuEntry(ComposeAct.SWITCH).takeIf { sendable },
        MenuEntry(ComposeAct.SAVE_DRAFT, separatorAbove = sendable),
    ) + text.map { MenuEntry(ComposeAct.valueOf(it.name)) } + MenuEntry(ComposeAct.CLOSE, separatorAbove = true)
    return if (sendable) Menu(entries, highlighted = 0) else Menu(entries, highlighted = 0).withSwitchApplication(ComposeAct.SWITCH)
}
