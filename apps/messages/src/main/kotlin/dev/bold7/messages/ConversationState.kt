package dev.bold7.messages

import dev.bold7.hub.data.numberKey
import dev.bold7.ui.Menu
import dev.bold7.ui.TextAct
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.withSwitchApplication

// The thread's screen state and its reducer, pure (docs/ARCHITECTURE.md §3):
// ConversationActivity renders it, Conversation runs the effects and feeds their results back.

/** The trackpad's focus on the field at the bottom (sim-50, C). */
const val COMPOSER = -1

/** On the To field of a new message (sim-49, C). */
const val TO = -2

/** On the smiley button right of the field (sim-146, C): a step right from the field's end. */
const val SMILEY = -3

/** What the screen says once, in OS 7's status message. */
enum class Notice {
    NOT_SENT, NOT_DELETED, NOT_CREATED, NO_RECIPIENT, NOT_CHOSEN,

    /** a file a text does not take, one unreadable, one too heavy for the carrier. */
    NOT_ATTACHABLE, NOT_ATTACHED, TOO_LARGE,

    /** Another app's conversation: its reply gone with its notification, refused by the app, or never shown back (D). */
    REPLY_GONE, REPLY_CANCELED, UNCONFIRMED,
}

data class ConversationState(
    /** Telephony's thread; null for a new message until CreateOneOnOneConversation answers. */
    val threadId: Long? = null,
    /** The band's name (sim-50: "513", C): the contact or number, as the inbox showed it. */
    val title: String = "",
    /** What is typed in To's last line, the empty one under the recipients (gsm 025, C): a number or a name. */
    val to: String = "",
    /** A new message's recipients, a To line each. */
    val recipients: List<Recipient> = emptyList(),
    /** The address book, read off the main thread for To's proposals. */
    val contacts: List<Contact> = emptyList(),
    /** On [TO]: the line under the trackpad, a recipient's or the typing one ([recipients]' size). */
    val line: Int = 0,
    /** On [TO]'s typing line: the proposal under the trackpad, -1 for none (the line itself). */
    val pick: Int = -1,
    /**
     * A new message to one chosen recipient who already has a conversation: that thread, whose
     * history shows before sending (D: gsm 025 shows none).
     */
    val found: Long? = null,
    /** The contact's picture in the band (gsm 023, C), its thumbnail's URI; null: the default one. */
    val photo: String? = null,
    val messages: List<ThreadMessage> = emptyList(),
    val loaded: Boolean = false,
    val draft: String = "",
    /** The file sent with the text (Attach): one at a time (D). */
    val attachment: Attachment? = null,
    /** [COMPOSER], [TO], [SMILEY], or a bubble's index in [messages]. */
    val focus: Int = if (threadId == null) TO else COMPOSER,
    /** A message on its way to Google Messages: the field waits for its answer. */
    val sending: Boolean = false,
    val notice: Notice? = null,
    /** A group's members' contact names, by number, for their bubbles. */
    val names: Map<String, String> = emptyMap(),
    /** Another app's conversation: its notification's key, [threadId] [APP_THREAD]; null for Telephony's. */
    val notification: String? = null,
    /** That app's package. */
    val app: String? = null,
    /** Its live notification can be answered: the field takes text; otherwise "Reply in <App>" (D). */
    val reply: Boolean = false,
) {
    /** The field takes text: always in a text's thread, in an app's while its reply is there. */
    val writable: Boolean get() = app == null || reply

    /**
     * The name above [m]'s bubble: [me] for a sent one; in a group, its sender's contact name or
     * number (gsm 023 names the contact, C); one person's, the band's name (D).
     */
    fun senderLabel(m: ThreadMessage, me: String): String {
        if (m.out) return me
        // An app's group names its senders (appMessages); one person, the band's name.
        if (app != null) return m.from ?: title
        val name = title.ifEmpty { recipients.singleOrNull()?.let { it.name ?: it.number }.orEmpty() }
        val from = m.from ?: return name
        val group = messages.mapNotNullTo(HashSet()) { it.from?.let(::numberKey) }.size > 1
        if (name.isNotEmpty() && !group) return name
        return names.entries.firstOrNull { sameNumber(it.key, from) }?.value ?: from
    }

    /** Something to send: text, or an attachment alone. */
    val hasContent: Boolean get() = draft.isNotBlank() || attachment != null
    val sendable: Boolean get() = hasContent && !sending && (threadId != null || addressed) && writable
    val focusedMessage: ThreadMessage? get() = messages.getOrNull(focus)

    /** A new message's only chosen recipient (a number typed and not taken does not count); null otherwise. */
    val single: String? get() = if (threadId == null) recipients.singleOrNull()?.number else null

    /** The thread the field reads and writes: the open one, or the one found for [single]. */
    val shownThread: Long? get() = threadId ?: found

    /** The contacts proposed for the name typed in To: worked out once per state, the list's adapter reads it often. */
    val suggestions: List<Contact> by lazy(LazyThreadSafetyMode.NONE) { suggestions(contacts, to) }

    /** Who a new message goes to: the recipients, then a number typed and not yet taken; each once. */
    val destinations: List<String> get() = (recipients.map { it.number } + listOfNotNull(dialable(to))).distinctBy(::numberKey)

    /** Someone to send to, and no name typed that is not chosen (part of a group would be missing). */
    val addressed: Boolean get() = destinations.isNotEmpty() && (to.isBlank() || dialable(to) != null)

    /** The band's name once the conversation exists: its members as To showed them (D, joined for a group). */
    val membersTitle: String get() =
        (recipients + listOfNotNull(dialable(to)?.takeIf { d -> recipients.none { sameNumber(it.number, d) } }?.let(::Recipient))).joinToString(", ") { it.shown }
}

sealed interface ThreadEvent {
    data class Loaded(val messages: List<ThreadMessage>) : ThreadEvent
    data class Step(val down: Boolean) : ThreadEvent
    /** A touch on a bubble. */
    data class Moved(val to: Int) : ThreadEvent
    /** A character for To, which is not an editor (the field's text comes by [Edited]). */
    data class Typed(val char: Char) : ThreadEvent
    /**
     * The field's editor went from [before] to [text]: typed through the input method, or an
     * emoticon put in. [before] is what the editor showed, which the draft may have left meanwhile
     * (a send ending on another thread): then only what was added at its end is kept.
     */
    data class Edited(val before: String, val text: String) : ThreadEvent
    /** Right from the field's end, or left from the smiley button. */
    data class Side(val right: Boolean) : ThreadEvent
    /** A touch on the field: the focus goes there. */
    data object FieldTouched : ThreadEvent
    data object Erased : ThreadEvent
    data object FieldCleared : ThreadEvent
    data object Sending : ThreadEvent
    data class Created(val threadId: Long) : ThreadEvent
    /** [text] and [attachment] left: what was typed or attached since stays in the field. */
    data class Sent(val text: String, val attachment: Attachment? = null) : ThreadEvent
    data class Failed(val notice: Notice) : ThreadEvent
    data object Noticed : ThreadEvent
    /** A file copied for the field; it takes the place of the one there. */
    data class Attached(val attachment: Attachment) : ThreadEvent
    data object AttachmentRemoved : ThreadEvent
    data class ContactsLoaded(val contacts: List<Contact>) : ThreadEvent
    /** A group's members named from the contacts. */
    data class Names(val names: Map<String, String>) : ThreadEvent
    /** A recipient chosen: a proposal, a number typed, or Choose Contact's. */
    data class Chosen(val recipient: Recipient) : ThreadEvent
    /** A touch on To's [line]. */
    data class ToTouched(val line: Int) : ThreadEvent
    data class Photo(val uri: String?) : ThreadEvent
    /** [number]'s one-to-one conversation in Telephony, null: none. Dropped when [number] is no longer alone in To. */
    data class Found(val number: String, val threadId: Long?) : ThreadEvent
    /** An app's conversation: whether its notification can be answered now. */
    data class Replyable(val reply: Boolean) : ThreadEvent
    /** Something to say once, nothing else changed. */
    data class Told(val notice: Notice) : ThreadEvent
}

/**
 * The number to look up after [old] became [new]: the only chosen recipient of a new message,
 * when it changed; null: nothing to look up.
 */
fun lookUp(old: ConversationState, new: ConversationState): String? {
    val n = new.single ?: return null
    val o = old.single
    return n.takeIf { o == null || !sameNumber(o, n) }
}

/** [list] as To's recipients; another only recipient than before leaves the history found for the last. */
private fun ConversationState.withRecipients(list: List<Recipient>): ConversationState {
    val next = copy(recipients = list)
    val same = single != null && next.single != null && sameNumber(single!!, next.single!!)
    return if (same || found == null) next else next.copy(found = null, messages = emptyList(), loaded = false)
}

fun ConversationState.reduce(event: ThreadEvent): ConversationState = when (event) {
    is ThreadEvent.Loaded -> {
        // The focused bubble stays on its message, gone: the field.
        val uri = focusedMessage?.uri
        val at = if (uri == null) -1 else event.messages.indexOfFirst { it.uri == uri }
        copy(messages = event.messages, loaded = true, focus = if (focus >= 0) (if (at >= 0) at else COMPOSER) else focus)
    }
    is ThreadEvent.Step -> if (threadId == null) stepTo(event.down) else copy(focus = step(event.down))
    is ThreadEvent.Moved -> copy(focus = event.to.coerceIn(0, messages.lastIndex.coerceAtLeast(0)).takeIf { messages.isNotEmpty() } ?: COMPOSER)
    is ThreadEvent.Typed -> if (focus == TO) copy(to = to + event.char, line = recipients.size, pick = -1) else copy(draft = draft + event.char, focus = COMPOSER)
    is ThreadEvent.Edited -> when {
        event.text == draft -> this
        event.before == draft -> copy(draft = event.text, focus = COMPOSER)
        event.text.startsWith(event.before) -> copy(draft = draft + event.text.substring(event.before.length), focus = COMPOSER)
        // An edit inside a text the draft has left: the draft stands, the editor shows it again.
        else -> this
    }
    is ThreadEvent.Side -> when {
        event.right && focus == COMPOSER -> copy(focus = SMILEY)
        !event.right && focus == SMILEY -> copy(focus = COMPOSER)
        else -> this
    }
    ThreadEvent.FieldTouched -> copy(focus = COMPOSER)
    ThreadEvent.Erased -> when {
        focus != TO -> copy(draft = draft.dropLast(1), focus = COMPOSER)
        // On a recipient's line, Delete takes it out (D).
        line < recipients.size -> withRecipients(recipients.filterIndexed { i, _ -> i != line })
        else -> copy(to = to.dropLast(1), pick = -1)
    }
    ThreadEvent.FieldCleared -> copy(draft = "", focus = COMPOSER)
    ThreadEvent.Sending -> copy(sending = true)
    is ThreadEvent.Created -> copy(threadId = event.threadId, title = title.ifEmpty { membersTitle }, focus = COMPOSER)
    is ThreadEvent.Sent -> copy(draft = draft.removePrefix(event.text), sending = false, attachment = attachment.takeIf { it != event.attachment })
    is ThreadEvent.Failed -> copy(sending = false, notice = event.notice)
    ThreadEvent.Noticed -> copy(notice = null)
    is ThreadEvent.Attached -> copy(attachment = event.attachment, focus = COMPOSER)
    ThreadEvent.AttachmentRemoved -> copy(attachment = null)
    is ThreadEvent.ContactsLoaded -> copy(contacts = event.contacts, pick = -1)
    is ThreadEvent.Names -> copy(names = event.names)
    is ThreadEvent.Chosen -> {
        val added = if (recipients.any { sameNumber(it.number, event.recipient.number) }) recipients else recipients + event.recipient
        // The field next (D): one recipient is the common case; up comes back to To.
        withRecipients(added).copy(to = "", pick = -1, line = added.size, focus = COMPOSER)
    }
    is ThreadEvent.ToTouched -> if (threadId != null) this else copy(focus = TO, line = event.line.coerceIn(0, recipients.size), pick = -1)
    is ThreadEvent.Photo -> copy(photo = event.uri)
    is ThreadEvent.Replyable -> copy(reply = event.reply)
    is ThreadEvent.Told -> copy(notice = event.notice)
    is ThreadEvent.Found -> {
        val n = single
        if (n == null || !sameNumber(n, event.number) || event.threadId == found) this
        else copy(found = event.threadId, messages = emptyList(), loaded = false)
    }
}

/**
 * A new message: To's lines from the top, the typing line's proposals, then the field; up from
 * the field comes back to the typing line. The ends hold.
 */
private fun ConversationState.stepTo(down: Boolean): ConversationState = when {
    focus != TO -> if (down) this else copy(focus = TO, line = recipients.size, pick = -1)
    down && line < recipients.size -> copy(line = line + 1)
    down && pick < suggestions.lastIndex -> copy(pick = pick + 1)
    down -> copy(focus = COMPOSER, pick = -1)
    pick >= 0 -> copy(pick = pick - 1)
    else -> copy(line = (line - 1).coerceAtLeast(0))
}

/**
 * The click on To (guide 7.1 p. 103, B: a number typed, then Enter): on the typing line, a number
 * becomes a recipient; a name, the proposal under the trackpad, or the first (D). Null: nothing to
 * take.
 */
fun ConversationState.toClick(): ToChoice? {
    if (focus != TO || line < recipients.size) return null
    dialable(to)?.let { return ToChoice.One(Recipient(it)) }
    val proposed = suggestions
    return proposed.getOrNull(pick.coerceAtLeast(0))?.let(::choice)
}

/** The bubbles from the top, then the field. The ends hold. */
private fun ConversationState.step(down: Boolean): Int = when {
    focus == COMPOSER || focus == SMILEY -> if (down || messages.isEmpty()) focus else messages.lastIndex
    down -> if (focus >= messages.lastIndex) COMPOSER else focus + 1
    else -> (focus - 1).coerceAtLeast(0)
}

/** The thread's actions (guide 7.1 p. 105, B; sim-51, sim-52, C); Attach and its kinds (guide p. 104, B). */
enum class ThreadAct {
    CHOOSE_CONTACT, SEND, DELETE_MESSAGE, CLEAR_FIELD, CLEAR_CONVERSATION, ADD_SMILEY,
    ATTACH, ATTACH_PICTURE, ATTACH_VIDEO, ATTACH_AUDIO, ATTACH_FILE, REMOVE_ATTACHMENT, OPEN_ATTACHMENT, SWITCH, CLOSE,
    /** The field's selection entries (core/ui's TextAct); named as it names them. */
    START_SELECTION, CUT, COPY, PASTE,
    /** An app's conversation: "Open in <App>", and Mark Unread (D). */
    OPEN_IN_APP, MARK_UNREAD,
}

/** Where Add Smiley and Attach apply: the field, and the To band of a new message, which has the field under it (sim-51, C; D). */
private fun ConversationState.onField() = writable && (focus == COMPOSER || focus == SMILEY || (threadId == null && focus == TO))

/**
 * The thread's menu, full or short: on a bubble Delete Message ("to delete an individual message
 * from the history, highlight the message > Delete Message", guide p. 105, B); Send while there is
 * text (sim-51, sim-52, C: first and focused); Clear Field (C); Clear Conversation, which keeps the
 * chat open (guide p. 105, B; C); Add Smiley on the field, after Clear Conversation (sim-150, C).
 * Attach, a submenu ("press the Menu key > Attach. Click an attachment type", guide p. 104, B;
 * its arrow, sim-292, C), in a group of its own at the end as in the mail's composer (sim-292,
 * sim-293, C), on the field; Remove Attachment in its place once one is there (D); Open Attachment
 * on a bubble that has one (guide p. 131, B; its place, D). Add to Contacts,
 * Check Spelling, Switch Input Language/Method and Show Symbols are not there. Start Selection, or Cut and Copy on a selection, then Paste when the clipboard has
 * something, are the full menu's only ([text], on the field: sim-150, C, puts Start Selection
 * between Clear Conversation and Add Smiley; Paste's place D). Switch Application is
 * [fullThreadMenu]'s, not the short menu's.
 */
fun threadMenu(s: ConversationState, text: List<TextAct> = emptyList()): List<MenuEntry<ThreadAct>> {
    val groups = listOf(
        // An app's conversation: the app itself first, as Open is in the inbox (D).
        listOfNotNull(ThreadAct.OPEN_IN_APP.takeIf { s.app != null }),
        // "In the To field ... Press the Menu key > Choose Contact" (guide 7.1 p. 103, B); its place, D.
        listOfNotNull(ThreadAct.CHOOSE_CONTACT.takeIf { s.threadId == null && s.focus == TO }),
        listOfNotNull(
            ThreadAct.OPEN_ATTACHMENT.takeIf { s.focusedMessage?.opens != null },
            // A reply on its way is the app's now: nothing to delete yet.
            ThreadAct.DELETE_MESSAGE.takeIf { s.focusedMessage?.uri?.startsWith(PENDING_URI) == false },
        ),
        listOfNotNull(ThreadAct.SEND.takeIf { s.hasContent && s.writable }, ThreadAct.CLEAR_FIELD.takeIf { s.draft.isNotEmpty() }),
        listOfNotNull(
            ThreadAct.CLEAR_CONVERSATION.takeIf { s.threadId != null && s.messages.isNotEmpty() },
            // The latest received message unread again, the thread closed (D).
            ThreadAct.MARK_UNREAD.takeIf { s.app != null && s.messages.any { !it.out } },
        ) + (if (s.writable && (s.focus == COMPOSER || s.focus == SMILEY)) text.map { ThreadAct.valueOf(it.name) } else emptyList()) + listOfNotNull(
            ThreadAct.ADD_SMILEY.takeIf { s.onField() },
        ),
        // An app's reply carries text only.
        listOfNotNull((if (s.attachment == null) ThreadAct.ATTACH else ThreadAct.REMOVE_ATTACHMENT).takeIf { s.onField() && s.app == null }),
    ).filter { it.isNotEmpty() }
    // A line between two groups (sim-51, C).
    return groups.flatMapIndexed { g, acts -> acts.mapIndexed { i, a -> MenuEntry(a, separatorAbove = g > 0 && i == 0, submenu = a == ThreadAct.ATTACH) } }
}

/** Attach's submenu, in the place of the menu as the Calendar's View (D): its kinds (D). */
fun attachMenu(): List<MenuEntry<ThreadAct>> =
    listOf(ThreadAct.ATTACH_PICTURE, ThreadAct.ATTACH_VIDEO, ThreadAct.ATTACH_AUDIO, ThreadAct.ATTACH_FILE).map { MenuEntry(it) }

/** The kind of file an entry of Attach's submenu picks. */
fun ThreadAct.attachKind(): AttachKind? = when (this) {
    ThreadAct.ATTACH_PICTURE -> AttachKind.PICTURE
    ThreadAct.ATTACH_VIDEO -> AttachKind.VIDEO
    ThreadAct.ATTACH_AUDIO -> AttachKind.AUDIO
    ThreadAct.ATTACH_FILE -> AttachKind.FILE
    else -> null
}

/**
 * The thread's full menu: Switch Application right after Send, which stays first and focused
 * (sim-51, C), else first like every full menu (sim-04, C), Close last; never empty, so the menu always opens.
 */
fun fullThreadMenu(s: ConversationState, text: List<TextAct> = emptyList()): Menu<ThreadAct> {
    val entries = threadMenu(s, text)
    val send = entries.indexOfFirst { it.action == ThreadAct.SEND }
    // Close ends the full menu (sim-289, C, as the list's).
    val close = MenuEntry(ThreadAct.CLOSE)
    return when {
        send >= 0 -> Menu(entries.take(send + 1) + MenuEntry(ThreadAct.SWITCH) + entries.drop(send + 1) + close, highlighted = send)
        entries.isEmpty() -> Menu(listOf(MenuEntry(ThreadAct.SWITCH)) + close, highlighted = 0)
        else -> Menu(entries + close, highlighted = 0).withSwitchApplication(ThreadAct.SWITCH)
    }
}

/** The entry the menu opens on: Send when there is one (sim-51, sim-52, C), else the first. */
fun defaultEntry(menu: List<MenuEntry<ThreadAct>>): Int = menu.indexOfFirst { it.action == ThreadAct.SEND }.coerceAtLeast(0)

/** A key, as the owner of a field (the thread, the inbox's search) reads it from Android's ([fieldKey]). */
enum class FieldKey { UP, DOWN, LEFT, RIGHT, CLICK, BACK, MENU, DELETE, TEXT }

/**
 * Whether the field's editor takes [key], and with it the input method (Typing: substitution,
 * capitals, accents, Sym), rather than the thread's keys of OS 7. The editor holds Android's focus
 * everywhere but on To, so the input method sees every key first; what it lets through comes here.
 * On the field: text, Delete and the caret's moves are the editor's, except right at its end, which
 * goes to the smiley button (sim-146, C). Up, down, the click (Enter sends, guide p. 103, B),
 * Escape and Menu are always OS 7's. On a bubble or the button, a letter or Delete still types in
 * the field (D, as before), the sides do not move its caret. To is drawn by the band, not an editor.
 */
fun editorTakes(key: FieldKey, focus: Int, caretAtEnd: Boolean): Boolean = when (key) {
    FieldKey.UP, FieldKey.DOWN, FieldKey.CLICK, FieldKey.BACK, FieldKey.MENU -> false
    FieldKey.LEFT -> focus == COMPOSER
    FieldKey.RIGHT -> focus == COMPOSER && !caretAtEnd
    FieldKey.TEXT, FieldKey.DELETE -> focus != TO
}

/**
 * Shift held with the trackpad up or down, the caret in the field: the line is selected, rather
 * than the focus going to a bubble (guide 7.1 p. 37, B). On a bubble, the button or To, the
 * trackpad keeps its steps.
 */
fun shiftSelects(key: FieldKey, focus: Int, shift: Boolean): Boolean =
    shift && focus == COMPOSER && (key == FieldKey.UP || key == FieldKey.DOWN)
