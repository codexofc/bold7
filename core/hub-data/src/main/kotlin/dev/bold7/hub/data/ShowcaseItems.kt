package dev.bold7.hub.data

import dev.bold7.showcase.CallType
import dev.bold7.showcase.Channel
import dev.bold7.showcase.Message
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.ShowcaseEvent
import dev.bold7.showcase.Thread

// The showcase (core/showcase) as the Hub's items: a made-up inbox for the apps that read the
// Hub or show its counts while the showcase is on. Pure: nothing here reaches the Hub, Telephony,
// the contacts or a mail account.

/** What every showcase item's source key starts with. */
const val SHOWCASE_KEY = "showcase:"

const val SHOWCASE_TEXTS = "showcase-sms"
const val SHOWCASE_MAIL = "showcase-mail"
const val SHOWCASE_CALLS = "showcase-calls"
private const val MINUTE = 60_000L

/** A showcase thread's id as an item's conversation: negative, below the measure's, one per thread. */
fun showcaseThreadId(id: Long) = -100 - id

private fun Message.time(now: Long) = now - minutesAgo * MINUTE

/** One person's name, or a group's first names. */
fun Thread.showcaseTitle() = members.singleOrNull()?.let { Showcase.person(it).name } ?: members.joinToString(", ") { Showcase.person(it).first }

/** The made-up items and the accounts that hold them. */
class ShowcaseHub(val items: List<HubItem>, val accounts: List<Account>)

/**
 * An item per text, per email and per missed call, as the Hub's sources make them. [text] shapes
 * a message the way a phone sends it (the emoticons as emojis), which only the apps' views know;
 * [event] how far the video's story went ([Showcase.threads]).
 */
fun showcaseHub(now: Long, event: ShowcaseEvent? = null, text: (String) -> String = { it }): ShowcaseHub {
    val threads = Showcase.threads(event)
    val texts = threads.filter { it.channel == Channel.TEXT }.flatMap { t ->
        val group = t.members.size > 1
        t.messages.mapIndexed { i, m ->
            val read = m.out || m.read
            HubItem(
                SHOWCASE_TEXTS, "${SHOWCASE_KEY}t${t.id}:$i", if (group) ItemKind.MMS else ItemKind.SMS, m.time(now), t.showcaseTitle(),
                address = t.members.joinToString(";") { Showcase.person(it).mobile }, preview = text(m.text),
                box = if (m.out) Box.SENT else Box.INBOX, conversationId = "${showcaseThreadId(t.id)}",
                read = read, sourceRead = read, addedAt = m.time(now),
            )
        }
    }
    val mails = threads.filter { it.channel == Channel.EMAIL }.map { t ->
        val p = Showcase.person(t.members.single())
        val m = t.last
        HubItem(
            SHOWCASE_MAIL, "${SHOWCASE_KEY}m${t.id}", ItemKind.EMAIL, m.time(now), p.name, address = p.email, subject = t.subject,
            preview = m.text, conversationId = "m${t.id}", read = m.read, sourceRead = m.read, attachmentCount = t.attachments, addedAt = m.time(now),
        )
    }
    val calls = Showcase.calls.withIndex().filter { it.value.type == CallType.MISSED }.map { (i, c) ->
        val time = now - c.minutesAgo * MINUTE
        HubItem(
            SHOWCASE_CALLS, "${SHOWCASE_KEY}c$i", ItemKind.MISSED_CALL, time, c.person?.let { Showcase.person(it).name } ?: c.number,
            address = c.number, read = !c.isNew, sourceRead = !c.isNew, addedAt = time,
        )
    }
    val accounts = listOf(
        Account(SHOWCASE_TEXTS, AccountKind.TEXT_MESSAGES, "Text Messages", position = 1),
        Account(SHOWCASE_MAIL, AccountKind.EMAIL, "Email", address = Showcase.OWNER_EMAIL, position = 2),
        Account(SHOWCASE_CALLS, AccountKind.CALLS, "Calls", position = 90),
    )
    return ShowcaseHub(texts + mails + calls, accounts)
}
