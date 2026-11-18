package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubAccounts
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ReplyResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The made-up thread's id: no thread of Telephony is negative. */
const val DEMO_THREAD = -1L

/** Telephony's rows of one thread, off the main thread only (AndroidThreadReader). */
interface ThreadReader {
    /** Null: unreadable (READ_SMS refused, provider gone). */
    fun read(threadId: Long): List<ThreadMessage>?

    /** Every thread, newest first, with its members' addresses (the user left out); null: unreadable. */
    fun threads(): List<Pair<Long, List<String>>>?
}

/** Android's contacts (AndroidContactBook, READ_CONTACTS), off the main thread only; unreadable reads as nothing. */
interface ContactBook {
    /** Every contact with a number, for To's proposals. */
    fun all(): List<Contact>

    /** The thumbnail's URI of the contact who has [number]; null without one. */
    fun photo(number: String): String?

    /** The name of the contact who has [number]; null without one. */
    fun name(number: String): String?

    /** The number Select Contact returned ([uri], a row of its data), as a recipient; null: unreadable. */
    fun chosen(uri: String): Recipient?
}

/** What became of a file picked for Attach. */
sealed interface Taken {
    data class Ok(val attachment: Attachment) : Taken
    data class Refused(val notice: Notice) : Taken
}

/**
 * Messages' own copies of the files attached (AndroidAttachmentStore): blocking, off
 * the main thread only.
 */
interface AttachmentStore {
    /** The file at [source] (the picker's URI) copied, shrunk if a picture must be, or why not. */
    fun take(source: String): Taken

    /** The copy is no longer wanted: sent, replaced or removed. */
    fun drop(attachment: Attachment)
}

/** A call Google Messages refused, with its gRPC code (UNAVAILABLE, PERMISSION_DENIED…). */
class MessagingApiException(val code: String, cause: Throwable? = null) : RuntimeException(code, cause)

/** Google Messages' ExternalMessagingApiService (GrpcMessagingApi), blocking: off the main thread only. Each call throws [MessagingApiException]. */
interface MessagingApi {
    /** Whether the door is open (GetServiceCapabilities, the contract), never throws. */
    fun state(): ApiState

    /** CreateOneOnOneConversation: the thread_id of [to]'s conversation, new or existing. */
    fun create(to: String): Long

    /** CreateMmsGroupConversation: the thread_id of a group of [to] (two or more, each once). */
    fun createGroup(to: List<String>): Long

    /**
     * SendMessage: Google Messages sends in RCS when the thread is, otherwise SMS or MMS; with an
     * [attachment], lent to it for this call only.
     */
    fun send(threadId: Long, text: String, attachment: Attachment? = null)

    fun run(call: ApiCall)
}

/**
 * One conversation's thread: the state the activity renders, the effects run on
 * [io] one at a time (a single thread): Telephony read, marked read through Google Messages,
 * messages sent, created and deleted. Never a message lost without a word: a send that fails
 * keeps its text in the field and sets a [Notice]; what Google Messages accepts it copies into
 * Telephony, where the thread reads it with its state (sending, sent, delivered, failed).
 * Another app's conversation ([openApp]) is the Hub's instead ([apps]): its items, its
 * notification's reply, nothing of Telephony nor of Google Messages.
 */
class Conversation(
    private val reader: ThreadReader,
    private val api: MessagingApi,
    private val contacts: ContactBook,
    private val store: AttachmentStore,
    private val apps: AppThreads,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** A call that failed: logged by the caller (never its text). */
    private val onError: (RuntimeException) -> Unit,
) {
    private val mutable = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = mutable.asStateFlow()
    private val reading = AtomicBoolean(false)

    // An app's conversation, on [io] only: the Hub's bubbles, the replies awaiting their echo, those
    // already said not confirmed, and whether the thread was left (Mark Unread: nothing read again).
    private var hubMessages = emptyList<ThreadMessage>()
    private var pending = emptyList<Pending>()
    private val told = HashSet<Long>()
    private var replies = 0L
    @Volatile private var left = false

    /** Another app's conversation, the notification [key]'s, under [title]. */
    fun openApp(key: String, app: String, title: String) {
        mutable.value = ConversationState(threadId = APP_THREAD, title = title, notification = key, app = app)
        reload()
    }

    /** Shows [threadId]'s thread under [title], or a new message (null). */
    fun open(threadId: Long?, title: String) {
        mutable.value = ConversationState(threadId = threadId, title = title)
        if (threadId == null) readContacts() else lookUp(threadId)
        reload()
    }

    /**
     * Text from Contacts to [to], [title] its name: its conversation with its history
     * when there is one, else a new message, To filled and the field focused.
     */
    fun compose(to: String, title: String) {
        mutable.value = ConversationState(recipients = listOf(Recipient(to, title.ifEmpty { null })), line = 1, title = title, focus = COMPOSER)
        scope.launch(io) {
            val thread = reader.threads()?.let { threadFor(to, it) }
            if (thread == null) return@launch readContacts()
            // What was typed meanwhile stays.
            mutable.update { ConversationState(threadId = thread, title = title, draft = it.draft) }
            lookUp(thread)
            reload()
        }
    }

    /** Choose Contact's answer, a row of the contacts provider: its number in To. */
    fun choose(uri: String) {
        scope.launch(io) { event(contacts.chosen(uri)?.let(ThreadEvent::Chosen) ?: ThreadEvent.Failed(Notice.NOT_CHOSEN)) }
    }

    private fun readContacts() {
        scope.launch(io) { event(ThreadEvent.ContactsLoaded(contacts.all())) }
    }

    /** The band's picture: one person's, from the contacts (gsm 023, C); a group keeps the default one, and names its members. */
    private fun lookUp(thread: Long) {
        if (isMadeUp(thread)) return
        scope.launch(io) {
            val members = reader.threads()?.firstOrNull { it.first == thread }?.second ?: return@launch
            event(ThreadEvent.Photo(members.singleOrNull()?.let(contacts::photo)))
            // A group's bubbles name their senders (gsm 023, C).
            if (members.size > 1) event(ThreadEvent.Names(members.mapNotNull { n -> contacts.name(n)?.let { n to it } }.toMap()))
        }
    }

    /**
     * [count] made-up messages (at most DEMO_MAX), for the scrolling measure (tools/perf.sh, APP
     * mode, `--ei rows N`): nothing is read from Telephony nor asked of Google Messages.
     */
    fun demo(count: Int, now: Long) {
        val texts = listOf("Hi, are you free tonight?", "I will stop by around 8 pm.", "OK", "See you at 6 pm? I will be in front of the station, call me if you are late.")
        val messages = List(count.coerceIn(0, DEMO_MAX)) { i ->
            val out = i % 3 != 1
            ThreadMessage("demo:$i", now - (count - i) * 7 * 60_000L, out, texts[i % texts.size], if (out) Delivery.DELIVERED else Delivery.RECEIVED)
        }
        mutable.value = ConversationState(threadId = DEMO_THREAD, title = "Demo", messages = messages, loaded = true)
    }

    /** A made-up thread shown whole ([showcaseConversation]): nothing is read, sent nor deleted for it. */
    fun show(made: ConversationState) {
        mutable.value = made
    }

    /** The video's story moved on in the showcase thread shown: what came in is added under the bubbles ([arrived]). */
    fun arrive(made: ConversationState) {
        val s = mutable.value
        if (s.threadId != made.threadId) return
        val messages = arrived(s.messages, made.messages)
        if (messages.size != s.messages.size) event(ThreadEvent.Loaded(messages))
    }

    fun event(event: ThreadEvent) {
        var old = mutable.value
        var new = old
        while (true) {
            new = old.reduce(event)
            if (mutable.compareAndSet(old, new)) break
            old = mutable.value
        }
        if (new.found != old.found) reload()
        lookUp(old, new)?.let(::findHistory)
    }

    /** [number]'s one-to-one conversation, read only (never getOrCreateThreadId): its history before sending. */
    private fun findHistory(number: String) {
        scope.launch(io) { event(ThreadEvent.Found(number, reader.threads()?.let { threadFor(number, it) })) }
    }

    /** Reads the thread again (Telephony changed); marks it read when it shows an unread message. Changes coming during a read make one more. */
    fun reload() {
        val s = mutable.value
        if ((s.notification == null && isMadeUp(s.threadId)) || reading.getAndSet(true)) return
        scope.launch(io) {
            reading.set(false)
            s.notification?.let { return@launch readApp(it) }
            val thread = mutable.value.shownThread ?: return@launch
            val messages = reader.read(thread) ?: return@launch
            // The history of a recipient taken out meanwhile is not shown.
            if (mutable.value.shownThread != thread) return@launch
            event(ThreadEvent.Loaded(messages))
            if (messages.any { it.delivery == Delivery.UNREAD }) call { api.run(ApiCall.MarkConversationRead(thread)) }
        }
    }

    /**
     * The Hub's items of the conversation [key], and whether its notification can be answered; its
     * received messages read in the Hub alone: the app's own "mark as read" waits for the user's
     * Mark Opened in the inbox (D).
     */
    private fun readApp(key: String) {
        if (left) return
        val items = call { apps.items(key) } ?: return
        hubMessages = appMessages(items)
        event(ThreadEvent.Replyable(call { apps.canReply(key) } == true))
        showApp()
        val unread = items.filter { !it.read && !it.deleted && it.box == Box.INBOX }
        if (unread.isNotEmpty()) call { apps.write(HubContract.METHOD_MARK_READ, unread.map { ItemKey(it.accountId, it.sourceKey) }, hubOnly = true) }
    }

    /** The Hub's bubbles with the replies awaiting their echo; a reply past [ECHO_MS] without one is said once. */
    private fun showApp() {
        val at = now()
        val (messages, waiting) = withPending(hubMessages, pending, at)
        pending = waiting
        event(ThreadEvent.Loaded(messages))
        if (waiting.any { at - it.at >= ECHO_MS && told.add(it.id) }) event(ThreadEvent.Told(Notice.UNCONFIRMED))
    }

    /**
     * An app's conversation: the field's text through its notification's reply, by the Hub, which
     * checks the lock when it sends. Handed over, the text shows as sending until the app shows it
     * back; refused or gone, it stays in the field and the screen says why.
     */
    private fun reply(s: ConversationState, key: String) {
        if (!s.sendable) return
        val text = s.draft
        event(ThreadEvent.Sending)
        scope.launch(io) {
            when (call { apps.reply(key, text) }) {
                ReplyResult.SENT -> {
                    pending = pending + pendingReply(replies++, text, now(), hubMessages)
                    event(ThreadEvent.Sent(text))
                    showApp()
                    scope.launch(io) {
                        delay(ECHO_MS)
                        showApp()
                    }
                }
                ReplyResult.NO_REPLY -> {
                    event(ThreadEvent.Replyable(false))
                    event(ThreadEvent.Failed(Notice.REPLY_GONE))
                }
                ReplyResult.CANCELED -> event(ThreadEvent.Failed(Notice.REPLY_CANCELED))
                // Locked, or the Hub unreachable.
                ReplyResult.REFUSED, null -> event(ThreadEvent.Failed(Notice.NOT_SENT))
            }
        }
    }

    /** Mark Unread in an app's conversation: its latest received message, then nothing more is read (the thread closes). */
    suspend fun markUnread() {
        left = true
        withContext(io) {
            val last = hubMessages.lastOrNull { !it.out } ?: return@withContext
            call { apps.write(HubContract.METHOD_MARK_UNREAD, listOf(ItemKey(HubAccounts.NOTIFICATIONS.id, last.uri.removePrefix(HUB_URI))), hubOnly = false) }
        }
    }

    /**
     * Send: the conversation created first for a new message (one recipient's, or a group of
     * several), then the text; what fails is said, the text stays.
     */
    fun send(now: Long = System.currentTimeMillis()) {
        val s = mutable.value
        s.notification?.let { return reply(s, it) }
        if (isMadeUp(s.threadId)) {
            // The showcase's story (capture.sh): the text shows as sent in this screen only, nothing leaves.
            if (isShowcaseThread(s.threadId!!) && s.draft.isNotBlank() && !s.sending) {
                event(ThreadEvent.Loaded(s.messages + ThreadMessage("${SHOWCASE_KEY}sent:${s.messages.size}", now, true, s.draft, Delivery.DELIVERED)))
                event(ThreadEvent.Sent(s.draft))
            }
            return
        }
        if (!s.sendable) {
            if (s.hasContent && !s.sending && s.threadId == null) event(ThreadEvent.Failed(Notice.NO_RECIPIENT))
            return
        }
        val text = s.draft
        val attachment = s.attachment
        val to = s.destinations
        event(ThreadEvent.Sending)
        scope.launch(io) {
            // One recipient whose conversation shows: sent there, as Text from Contacts does.
            val known = s.found?.takeIf { to.size == 1 }
            val thread = s.threadId ?: known
                ?: (call { if (to.size == 1) api.create(to[0]) else api.createGroup(to) } ?: return@launch event(ThreadEvent.Failed(Notice.NOT_CREATED)))
            if (s.threadId == null) {
                event(ThreadEvent.Created(thread))
                lookUp(thread)
            }
            if (call { api.send(thread, text, attachment) } == null) return@launch event(ThreadEvent.Failed(Notice.NOT_SENT))
            event(ThreadEvent.Sent(text, attachment))
            // Google Messages has its own copy: ours goes.
            attachment?.let(store::drop)
            reload()
        }
    }

    /** Attach: the file picked at [source] copied for the field, in the place of the one there; refused, why. */
    fun attach(source: String) {
        if (isMadeUp(mutable.value.threadId)) return
        scope.launch(io) {
            when (val taken = store.take(source)) {
                is Taken.Refused -> event(ThreadEvent.Failed(taken.notice))
                is Taken.Ok -> {
                    val old = mutable.value.attachment
                    event(ThreadEvent.Attached(taken.attachment))
                    // The one replaced, unless it is leaving with a send.
                    if (old != null && !mutable.value.sending) store.drop(old)
                }
            }
        }
    }

    /** Remove Attachment: the field without it, its copy gone. */
    fun removeAttachment() {
        val old = mutable.value.attachment ?: return
        if (mutable.value.sending) return
        event(ThreadEvent.AttachmentRemoved)
        scope.launch(io) { store.drop(old) }
    }

    /** Delete Message or Clear Conversation: each message deleted in Google Messages, then read again. */
    fun delete(uris: List<String>) {
        if (mutable.value.notification != null) return deleteInHub(uris)
        if (isMadeUp(mutable.value.threadId)) return
        scope.launch(io) {
            // Stops at the first refusal: what went is gone, the rest stays, and the screen says so.
            if (uris.any { call { api.run(ApiCall.Delete(it)) } == null }) event(ThreadEvent.Failed(Notice.NOT_DELETED))
            reload()
        }
    }

    /** An app's messages deleted in the Hub (its notification cancelled once all are, HubEffects); a reply on its way is not the Hub's. */
    private fun deleteInHub(uris: List<String>) {
        val keys = uris.filter { it.startsWith(HUB_URI) }.map { ItemKey(HubAccounts.NOTIFICATIONS.id, it.removePrefix(HUB_URI)) }
        if (keys.isEmpty()) return
        scope.launch(io) {
            if (call { apps.write(HubContract.METHOD_DELETE, keys, hubOnly = false) } == null) event(ThreadEvent.Failed(Notice.NOT_DELETED))
            reload()
        }
    }

    private inline fun <T> call(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        onError(e)
        null
    }
}
