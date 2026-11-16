package dev.bold7.messages

import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.SHOWCASE_MAIL
import dev.bold7.hub.data.showcaseHub
import dev.bold7.hub.data.showcaseTitle
import dev.bold7.showcase.Channel
import dev.bold7.showcase.Message
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.ShowcaseEvent
import dev.bold7.showcase.Thread
import dev.bold7.showcase.pick
import dev.bold7.ui.Emoticons

// The showcase (core/showcase) as Messages shows it: the made-up texts, emails and missed calls
// in the inbox, each thread and each email when opened. Pure: nothing here reaches Telephony,
// the Hub, the contacts, Google Messages or a mail account, and nothing is ever written.

/** What every showcase item's source key starts with. */
const val SHOWCASE_KEY = dev.bold7.hub.data.SHOWCASE_KEY

private const val MAIL = SHOWCASE_MAIL
private const val MINUTE = 60_000L

/** A showcase thread's id here: negative, below the measure's DEMO_THREAD, one per thread of the showcase. */
fun showcaseThreadId(id: Long) = dev.bold7.hub.data.showcaseThreadId(id)

fun isShowcaseThread(thread: Long) = thread <= -100

/** A made-up thread, the measure's or the showcase's: nothing is read, sent, attached nor deleted for it. */
fun isMadeUp(thread: Long?) = thread != null && thread < 0

private fun Message.time(now: Long) = now - minutesAgo * MINUTE

/** A text as a phone sends it: the emoticons typed as ":)" leave as their emojis (Emoticons), which the rows and bubbles draw back. */
private fun sent(text: String): String {
    val b = StringBuilder(text)
    Emoticons.find(text, shortcuts = true).asReversed().forEach { b.replace(it.start, it.end, Emoticons.ALL[it.index].emoji) }
    return b.toString()
}

/** The inbox: an item per text, per email and per missed call, as the Hub's sources make them, [event] played; never written back ([Snapshot.demo]). */
fun showcaseSnapshot(now: Long, event: ShowcaseEvent? = null): Snapshot = showcaseHub(now, event, ::sent).let { Snapshot(it.items, it.accounts, smsApp = null, dialer = null, demo = true) }

/** The showcase's inbox, its [format] the phone's own, the video's [event] played; writes go nowhere. */
class ShowcaseInbox(private val event: () -> ShowcaseEvent? = { null }, private val format: () -> InboxFormat) : InboxReader {
    override fun read() = showcaseSnapshot(System.currentTimeMillis(), event())
    override fun format() = format.invoke()
    override fun write(method: String, keys: List<ItemKey>) = Unit
    override fun markSeen(upTo: Map<String, Long>) = Unit
}

/**
 * The showcase's inbox while [on] says so (read at each call, off the main thread), the Hub's
 * otherwise: the real reader is built only the first time it is needed.
 */
class SwitchedInbox(private val on: () -> Boolean, real: () -> InboxReader, private val showcase: InboxReader) : InboxReader {
    private val real by lazy(real)
    private fun current() = pick(on(), { real }, { showcase })
    override fun read() = current().read()
    override fun format() = current().format()
    override fun write(method: String, keys: List<ItemKey>) = current().write(method, keys)
    override fun markSeen(upTo: Map<String, Long>) = current().markSeen(upTo)
}

/** The thread [threadId] ([showcaseThreadId]) shown whole, [event] played, its bubbles' senders named in a group; null if it is no showcase thread. */
fun showcaseConversation(threadId: Long, now: Long, event: ShowcaseEvent? = null): ConversationState? {
    val t = Showcase.threads(event).firstOrNull { it.channel == Channel.TEXT && showcaseThreadId(it.id) == threadId } ?: return null
    val single = t.members.singleOrNull()
    val messages = t.messages.mapIndexed { i, m ->
        ThreadMessage(
            "${SHOWCASE_KEY}t${t.id}:$i", m.time(now), m.out, sent(m.text), if (m.out) Delivery.DELIVERED else Delivery.RECEIVED,
            from = if (single == null && !m.out) m.from?.let { Showcase.person(it).name } else null,
        )
    }
    return ConversationState(threadId = threadId, title = t.showcaseTitle(), photo = single?.let { Showcase.person(it).photo }, messages = messages, loaded = true)
}

/** The email [key] names, opened; null if it is no showcase email. */
fun showcaseMail(key: ItemKey, now: Long): Opened? {
    if (key.accountId != MAIL) return null
    val t = Showcase.threads.firstOrNull { it.channel == Channel.EMAIL && "${SHOWCASE_KEY}m${it.id}" == key.sourceKey } ?: return null
    val p = Showcase.person(t.members.single())
    val owner = Addr(Showcase.OWNER_EMAIL, Showcase.OWNER)
    return Opened(
        from = Addr(p.email.orEmpty(), p.name), to = listOf(owner), cc = emptyList(), replyTo = emptyList(), subject = t.subject.orEmpty(),
        date = t.last.time(now), messageId = null, references = emptyList(), text = t.last.text, own = owner.email,
    )
}

/** Whether [key] is one of the showcase's: nothing is written for it. */
fun isShowcase(key: ItemKey) = key.sourceKey.startsWith(SHOWCASE_KEY)

/**
 * The open thread once the video's story moved on: what [shown] has, then the messages of [data]
 * it lacks that came in. What the user sent here stays where it is; what [data] has them send is
 * left out, it is that one.
 */
fun arrived(shown: List<ThreadMessage>, data: List<ThreadMessage>): List<ThreadMessage> {
    val have = shown.mapTo(HashSet()) { it.uri }
    return shown + data.filter { !it.out && it.uri !in have }
}
