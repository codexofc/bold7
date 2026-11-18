package dev.bold7.hub.data

// The sources that need no role: Android's SMS and MMS, call log and calendar
// providers, and the notifications of every app. Pure: apps/hub reads the rows and the posted
// notifications, and calls HubSources from one thread at a time.

/** The Hub's own accounts. Order: Notifications, Text Messages, mail, Calls. */
object HubAccounts {
    val NOTIFICATIONS = Account("notifications", AccountKind.NOTIFICATIONS, "Notifications", position = 0)
    val TEXT_MESSAGES = Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages", position = 1)

    // Room for the mail accounts in between.
    val CALLS = Account("calls", AccountKind.CALLS, "Calls", position = 90)

    /** Placed by eye, after Calls: not sourced from a device capture. */
    val CALENDAR = Account("calendar", AccountKind.CALENDAR, "Calendar", position = 91)
}

/** A row of content://sms; [type] is Telephony.Sms.TYPE, [name] the contact of [address] if any. */
data class SmsRow(
    val id: Long,
    val threadId: Long,
    val address: String?,
    val name: String?,
    val date: Long,
    val body: String,
    val read: Boolean,
    val type: Int,
)

/** A row of content://mms with its text parts joined; [box] is Telephony.Mms.MESSAGE_BOX. */
data class MmsRow(
    val id: Long,
    val threadId: Long,
    val address: String?,
    val name: String?,
    val dateSeconds: Long,
    val subject: String?,
    val text: String,
    val read: Boolean,
    val box: Int,
    /** Parts other than text and SMIL. */
    val attachments: Int,
    /**
     * The members of its thread, the user left out (Telephony's threads and canonical addresses):
     * more than one, the thread is a group's.
     */
    val recipients: List<String> = emptyList(),
)

/** A group thread's members as smsto: joins them, else null (one member, or none known). */
fun groupAddress(recipients: List<String>): String? = recipients.takeIf { it.size > 1 }?.joinToString(";")

/** A row of CallLog.Calls; [type] is its TYPE, [new] its NEW, [isRead] its IS_READ (null: false). */
data class CallRow(
    val id: Long,
    val number: String?,
    val name: String?,
    val date: Long,
    val type: Int,
    val new: Boolean,
    val isRead: Boolean,
) {
    companion object {
        /** CallLog.Calls.MISSED_TYPE. */
        const val MISSED = 3
    }
}

/** A row of CalendarContract.Events, not deleted; [status] is its SELF_ATTENDEE_STATUS. */
data class EventRow(
    val id: Long,
    val title: String?,
    val organizer: String?,
    val start: Long,
    val status: Int,
    val isOrganizer: Boolean,
    val hasAttendees: Boolean,
) {
    companion object {
        /** CalendarContract.Attendees.ATTENDEE_STATUS_*: NONE 0, ACCEPTED 1, DECLINED 2, INVITED 3, TENTATIVE 4. */
        const val INVITED = 3
        val ANSWERS = setOf(1, 2, 4)
    }
}

/** Android's providers, read whole, blocking: on the sources' thread only. */
interface ProviderRows {
    fun sms(): List<SmsRow>
    fun mms(): List<MmsRow>
    fun calls(): List<CallRow>
    fun events(): List<EventRow>
}

/** What the listener copies of a StatusBarNotification, on its thread, before handing it over. */
data class Posted(
    /** StatusBarNotification.key: one notification, through its updates. */
    val key: String,
    val packageName: String,
    val postTime: Long,
    /** Notification.when; 0 if unset. */
    val whenTime: Long,
    val flags: Int,
    /** A MediaStyle template: a player, not a message. */
    val media: Boolean,
    val title: String?,
    val text: String?,
    val bigText: String?,
    /** MessagingStyle: the conversation's name (a group's, or its contact's). */
    val conversationTitle: String? = null,
    /** MessagingStyle's messages (EXTRA_MESSAGES), oldest first: each one an item. */
    val messages: List<PostedMessage> = emptyList(),
    /** The user (EXTRA_MESSAGING_PERSON) as [PostedMessage.senderKey] names a sender. */
    val userKey: String? = null,
    /** EXTRA_IS_GROUP_CONVERSATION. */
    val group: Boolean = false,
    /** Notification.category, null if none. */
    val category: String? = null,
    /** A MessagingStyle template. */
    val messaging: Boolean = false,
) {
    companion object {
        // Notification.FLAG_*, public values of API 34.
        const val FLAG_ONGOING_EVENT = 0x2
        const val FLAG_FOREGROUND_SERVICE = 0x40
        const val FLAG_GROUP_SUMMARY = 0x200
    }
}

/**
 * One message of a MessagingStyle notification. [senderKey] is its sender Person's key, else its
 * name, else the legacy sender text; null for a message of the user, as Android draws it.
 */
data class PostedMessage(val text: String, val timestamp: Long, val sender: String?, val senderKey: String?)

/** The user's own message: no sender, or the user's Person (MessagingLayout's rule, D). */
fun PostedMessage.fromMe(userKey: String?) = senderKey == null || senderKey == userKey

/**
 * Not a message: ongoing and foreground service notifications, players, group
 * summaries that double their children, and Bold7's own. Everything else enters.
 */
fun Posted.entersHub(): Boolean =
    flags and (Posted.FLAG_ONGOING_EVENT or Posted.FLAG_FOREGROUND_SERVICE or Posted.FLAG_GROUP_SUMMARY) == 0 &&
        !media && packageName != "dev.bold7" && !packageName.startsWith("dev.bold7.")

/** Packages whose notifications are system or tool noise, and the dialer (missed calls come from the call log). */
private val NOT_MESSAGES = setOf(
    "android", "com.android.shell", "com.android.systemui", "org.matrix.vector", "io.github.sds100.keymapper",
    "com.google.android.dialer", "com.android.dialer", "com.android.server.telecom", "com.android.phone",
    // Never messages: the accessibility review ("Review app with full device access"), Play services,
    // the Play Store, Android's settings (seen on the device).
    "com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.google.android.gms",
    "com.android.vending", "com.android.settings",
)

/**
 * A notification that is no message (Vector's "Module updated", the keyboard layout, a shell test):
 * stored for the banner's list, left out of the unified inbox. The store keeps no category, so
 * the package decides.
 */
fun HubItem.isSystemNotification() =
    kind == ItemKind.NOTIFICATION && folder?.let { it in NOT_MESSAGES || it.startsWith("ph.dgsd.benos.") } == true

private val MESSAGE_CATEGORIES = setOf("msg", "email", "social")

/**
 * A notification the unified inbox leaves out: a system one, or one that says
 * what it is and is no message, neither MessagingStyle nor of the category msg, email or social.
 * A row older than the category (null) keeps the package's rule alone.
 */
fun HubItem.isNotMessage() =
    isSystemNotification() || isSendFailure() ||
        (kind == ItemKind.NOTIFICATION && category != null && !messaging && category !in MESSAGE_CATEGORIES)

private const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"

/**
 * Google Messages' "can't send" notice. A failed send is no received message (OS 7 shows it on the
 * sent message itself): every message of that app is tagged by its conversation
 * ("...:incoming_message:<id>"), the status notices carry no tag, the fourth field of the
 * notification key (user|package|id|tag|uid).
 */
private fun HubItem.isSendFailure() =
    kind == ItemKind.NOTIFICATION && folder == GOOGLE_MESSAGES && conversationId?.split('|')?.getOrNull(3) == "null"

private fun boxOf(type: Int) = when (type) {
    1 -> Box.INBOX
    2 -> Box.SENT
    3 -> Box.DRAFT
    // Outbox, failed, queued: waiting to leave.
    else -> Box.OUTBOX
}

private fun String?.orNull() = this?.takeIf { it.isNotBlank() }

internal fun SmsRow.toItem() = HubItem(
    HubAccounts.TEXT_MESSAGES.id, "sms:$id", ItemKind.SMS, date, name.orNull() ?: address.orEmpty(),
    address = address, preview = body, box = boxOf(type), conversationId = "$threadId", read = read, sourceRead = read,
)

internal fun MmsRow.toItem() = HubItem(
    HubAccounts.TEXT_MESSAGES.id, "mms:$id", ItemKind.MMS, dateSeconds * 1000, name.orNull() ?: address.orEmpty(),
    // A group's address names every member, so that opening it opens the group.
    address = groupAddress(recipients) ?: address, subject = subject.orNull(), preview = text, box = boxOf(box), conversationId = "$threadId",
    read = read, attachmentCount = attachments, sourceRead = read,
)

internal fun CallRow.toItem() = (!new || isRead).let { read ->
    HubItem(
        HubAccounts.CALLS.id, "$id", ItemKind.MISSED_CALL, date, name.orNull() ?: number.orEmpty(),
        address = number, read = read, sourceRead = read,
    )
}

internal fun EventRow.isInvitation() = !isOrganizer && hasAttendees && (status == EventRow.INVITED || status in EventRow.ANSWERS)

/** Read once answered, or once its meeting has started: past, it no longer asks for anything. */
internal fun EventRow.toItem(now: Long) = (status != EventRow.INVITED || start <= now).let { read ->
    HubItem(
        HubAccounts.CALENDAR.id, "$id", ItemKind.INVITATION, start, organizer.orEmpty(),
        address = organizer, subject = title.orNull(), read = read, sourceRead = read,
    )
}

/** The notification of an item of the Notifications account: its key without "@<time>" (toItems). */
fun notificationKeyOf(sourceKey: String) = sourceKey.substringBeforeLast('@')

/**
 * The items of [this]: one per MessagingStyle message, keyed by its time, so that an update showing
 * the same messages again writes nothing new and a conversation keeps every message it showed. Two
 * messages of one time are told apart by their text, the same text at the same time is one
 * message. The user's own messages are sent ones, read, named after the conversation. A group's
 * name is the subject (only a group's: a 1:1 conversation title is its contact). Without messages,
 * one item of the title and text, keyed by Notification.when.
 */
internal fun Posted.toItems(): List<HubItem> {
    val subject = conversationTitle.orNull()?.takeIf { group }
    fun item(key: String, time: Long, sender: String, preview: String, mine: Boolean = false) = HubItem(
        HubAccounts.NOTIFICATIONS.id, key, ItemKind.NOTIFICATION, time, sender, subject = subject, preview = preview,
        box = if (mine) Box.SENT else Box.INBOX, folder = packageName, conversationId = this.key, read = mine, sourceRead = mine,
        category = category.orEmpty(), messaging = messaging,
    )
    if (messages.isEmpty()) return listOf(item("$key@${if (whenTime > 0) whenTime else postTime}", postTime, title.orEmpty(), bigText ?: text.orEmpty()))
    // My messages are named after the conversation: its title, else the one I write to (the last
    // sender who is not me), never the notification's title, which some apps (Instagram) set to my name.
    val other = conversationTitle.orNull() ?: messages.lastOrNull { !it.fromMe(userKey) }?.sender.orNull() ?: title.orEmpty()
    val unique = messages.distinctBy { it.timestamp to it.text }
    val sharing = unique.groupingBy { it.timestamp }.eachCount()
    return unique.map { m ->
        val time = if (m.timestamp > 0) m.timestamp else postTime
        val shared = if ((sharing[m.timestamp] ?: 0) > 1) "#${Integer.toHexString(m.text.hashCode())}" else ""
        val mine = m.fromMe(userKey)
        item("$key@$time$shared", time, if (mine) other else m.sender.orNull() ?: title.orEmpty(), m.text, mine)
    }
}

/** How long the Hub keeps what another app's notifications showed: 30 days (the maintainer's choice). */
const val KEEP_NOTIFICATIONS_MS = 30 * 24 * 60 * 60_000L

/**
 * The keys of the notifications' items older than [KEEP_NOTIFICATIONS_MS] at [now], but those of
 * the notifications still [posted]: forgotten, they would come back as new at the next update.
 */
fun expired(items: List<HubItem>, now: Long, posted: Set<String>): List<String> = items.filter {
    it.accountId == HubAccounts.NOTIFICATIONS.id && it.timestamp < now - KEEP_NOTIFICATIONS_MS && it.conversationId !in posted
}.map { it.sourceKey }

/**
 * [fresh] as its source reads it again, with what the Hub holds of it ([stored]): the Hub's read
 * state while the source's own has not changed, and its deletion. A new item is the source's.
 */
internal fun held(fresh: HubItem, stored: HubItem?): HubItem = when {
    stored == null || !fresh.sameMessage(stored) -> fresh
    else -> fresh.copy(read = if (fresh.sourceRead != stored.sourceRead) fresh.read else stored.read, deleted = stored.deleted)
}

/**
 * Whether [stored] is the message [this] reads again, and not an earlier one whose key the source
 * gave again: Telephony's tables have no AUTOINCREMENT, so the _id of the last rows deleted comes
 * back with the next message, of any thread. A message keeps its time and its conversation (an
 * invitation's time is the stored one, adjusted before). An item the Hub stored before the message
 * was written, by more than [CLOCK_SLACK_MS], is not it either: the builds before this rule merged a
 * new message into a deleted one so, keeping the earlier arrival and deletion.
 */
internal fun HubItem.sameMessage(stored: HubItem) = timestamp == stored.timestamp && conversationId == stored.conversationId &&
    (stored.addedAt == 0L || stored.addedAt >= timestamp - CLOCK_SLACK_MS)

/** How far the phone's clock may have been set back between a message's writing and the Hub's reading (D). */
internal const val CLOCK_SLACK_MS = 10 * 60_000L

private const val DAY_MS = 24 * 60 * 60_000L

private fun HubItem.sameContent(other: HubItem) =
    sender == other.sender && subject == other.subject && preview == other.preview

/**
 * Fills [sink] from the providers' [rows] and the posted notifications. Not thread safe: one
 * caller at a time (apps/hub's sources thread). Each method returns how many items it wrote.
 */
class HubSources(private val sink: HubSink, private val rows: ProviderRows, private val now: () -> Long) {
    private val accountsPut = HashSet<String>()

    /** The items of each notification still posted, by key, against reposts without anything new. */
    private val last = HashMap<String, Map<String, HubItem>>()

    /** The sources the user turned off ([disable]): account ids and packages. */
    @Volatile var disabled: Set<String> = DEFAULT_DISABLED_SOURCES
        private set

    /** When the notifications last expired; once a day at most, on the sources' own writes. */
    private var expiredAt: Long? = null

    private suspend fun account(account: Account) {
        if (accountsPut.add(account.id)) sink.putAccount(account)
    }

    /**
     * Makes the account's items those of [fresh]: writes what changed, forgets what the provider
     * lost. Writes nothing, and announces nothing, when nothing changed. [adjust] sees each fresh
     * item with its stored self.
     *
     * An [emptyRead] of the provider forgets nothing: an appop on ignore answers an empty cursor,
     * and the whole account would come back later as new.
     * Simplification: a provider really emptied (call log cleared) keeps its items until its next row.
     */
    private suspend fun sync(
        account: Account,
        emptyRead: Boolean,
        fresh: List<HubItem>,
        adjust: (HubItem, HubItem?) -> HubItem = { f, _ -> f },
    ): Int {
        account(account)
        if (emptyRead || account.id in disabled) return 0
        val kept = sink.items(account.id).associateBy { it.sourceKey }
        // A key given to another message: the earlier one goes, and the new one arrives as new
        // (its own arrival, nothing of the earlier's read or deleted state).
        val replaced = fresh.mapNotNull { f -> kept[f.sourceKey]?.takeIf { !adjust(f, it).sameMessage(it) }?.sourceKey }.toSet()
        if (replaced.isNotEmpty()) sink.remove(account.id, replaced)
        val stored = kept - replaced
        val put = fresh.map { held(adjust(it, stored[it.sourceKey]), stored[it.sourceKey]) }.filter { stored[it.sourceKey]?.copy(addedAt = 0) != it }
        val gone = stored.keys - fresh.mapTo(HashSet()) { it.sourceKey }
        if (put.isNotEmpty()) sink.put(put)
        if (gone.isNotEmpty()) sink.remove(account.id, gone)
        return put.size + gone.size + replaced.size
    }

    // Simplification: each change reads the whole provider and the account's stored items, a few
    // thousand rows at most on this phone; by _id or date above the last sync if that grows.
    suspend fun syncTexts(): Int {
        val texts = rows.sms().map { it.toItem() } + rows.mms().map { it.toItem() }
        return sync(HubAccounts.TEXT_MESSAGES, texts.isEmpty(), texts)
    }

    suspend fun syncCalls(): Int {
        val calls = rows.calls()
        return sync(HubAccounts.CALLS, calls.isEmpty(), calls.filter { it.type == CallRow.MISSED }.map { it.toItem() })
    }

    /**
     * The calendar keeps no arrival time: an invitation is dated when the Hub first sees it, or at
     * its meeting if that is earlier, so a meeting to come does not sit at the top until it starts.
     */
    suspend fun syncInvitations(): Int {
        val now = now()
        val events = rows.events()
        return sync(HubAccounts.CALENDAR, events.isEmpty(), events.filter { it.isInvitation() }.map { it.toItem(now) }) { fresh, stored ->
            fresh.copy(timestamp = stored?.timestamp ?: minOf(fresh.timestamp, now))
        }
    }

    /** Writes what [posted] shows that is new, if it enters the Hub. */
    suspend fun posted(posted: Posted): Int {
        expire()
        if (!posted.entersHub() || posted.packageName in disabled) return 0
        val items = posted.toItems()
        val known = last[posted.key].orEmpty()
        val written = if (posted.messages.isEmpty()) {
            // Without messages, the same content again is nothing new, whatever its time.
            if (known.values.maxByOrNull { it.timestamp }?.sameContent(items.single()) == true) return 0
            items.map { held(it, sink.item(it.accountId, it.sourceKey)) }
        } else {
            val oldest = now() - KEEP_NOTIFICATIONS_MS
            items.filter { it.timestamp >= oldest && known[it.sourceKey]?.sameContent(it) != true }.mapNotNull { item ->
                // A message the Hub already has, read in its app since, stays as the Hub holds it.
                val stored = sink.item(item.accountId, item.sourceKey)
                if (stored != null && stored.sameContent(item) && stored.box == item.box) null else held(item, stored)
            }
        }
        if (written.isNotEmpty()) {
            account(HubAccounts.NOTIFICATIONS)
            sink.put(written)
        }
        last[posted.key] = items.associateBy { it.sourceKey }
        return written.size
    }

    /**
     * Forgets the notifications' items older than [KEEP_NOTIFICATIONS_MS], once a day at most: no
     * wake-up of its own, the next notification does it.
     */
    private suspend fun expire() {
        val now = now()
        if (expiredAt?.let { now - it < DAY_MS && now >= it } == true) return
        expiredAt = now
        val old = expired(sink.items(HubAccounts.NOTIFICATIONS.id), now, last.keys)
        if (old.isNotEmpty()) sink.remove(HubAccounts.NOTIFICATIONS.id, old)
    }

    /**
     * The user turned these sources off ([disabled]) or on again: the items of those now off are
     * forgotten, what they write next is not stored (a mail account's, through [GatedSink]). The
     * providers keep their texts, calls and invitations: those of a source turned on again come
     * back at its next read. Returns how many items it forgot.
     */
    suspend fun disable(sources: Set<String>): Int {
        disabled = sources
        // What was compared to the items forgotten, or to those of a source off, is compared again.
        last.clear()
        return sink.forget(sources)
    }

    /**
     * The notification is gone from Android: read in its app, dismissed, cancelled. Its items stay
     * in the Hub, which keeps the only trace, read.
     */
    suspend fun removed(key: String): Int {
        last.remove(key)
        return sink.markRead(HubAccounts.NOTIFICATIONS.id, listOf(key))
    }

    /**
     * The listener is bound, again or in a new process: what is posted now enters, without
     * rewriting what the Hub already holds of it. When [active] is [complete], what was removed
     * while the listener was not bound is read; a snapshot taken once unbound again is empty for
     * that reason alone, and marks nothing.
     */
    suspend fun connected(active: List<Posted>, complete: Boolean = true): Int {
        expire()
        val keys = active.mapTo(HashSet()) { it.key }
        last.keys.retainAll(keys)
        val stored = sink.items(HubAccounts.NOTIFICATIONS.id)
        // The latest item of each notification still posted, which a new process does not remember.
        // Not one its source read (removed): the same content posted again is a new item. One
        // read in the Hub alone is still posted, and its reposts are nothing new.
        stored.filter { !it.sourceRead && it.conversationId in keys }.groupBy { it.conversationId!! }
            .forEach { (key, items) -> last.putIfAbsent(key, items.associateBy { it.sourceKey }) }
        var written = active.sumOf { posted(it) }
        if (complete) {
            // Not removed yet only: the trace grows without limit, what is still posted does not.
            val gone = stored.filter { !it.sourceRead && it.conversationId != null && it.conversationId !in keys }.mapTo(HashSet()) { it.conversationId!! }
            if (gone.isNotEmpty()) written += sink.markRead(HubAccounts.NOTIFICATIONS.id, gone)
        }
        return written
    }
}
