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
    /** MessagingStyle: the group's name, and its latest message with its sender (null: me). */
    val conversationTitle: String? = null,
    val lastSender: String? = null,
    val lastMessage: String? = null,
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
 * Not a message: ongoing and foreground service notifications, players, group
 * summaries that double their children, and Bold7's own. Everything else enters.
 */
fun Posted.entersHub(): Boolean =
    flags and (Posted.FLAG_ONGOING_EVENT or Posted.FLAG_FOREGROUND_SERVICE or Posted.FLAG_GROUP_SUMMARY) == 0 &&
        !media && (packageName == ESSAI || packageName != "dev.bold7" && !packageName.startsWith("dev.bold7."))

/** The test app (tools/essai) stands for a third-party app: its made-up notifications enter like any other's. */
private const val ESSAI = "dev.bold7.essai"

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

/**
 * One item per message: an update of the notification with a new time is a new item of the same
 * conversation (the notification's key), so a conversation keeps every message it showed.
 */
/** The notification of an item of the Notifications account: its key without "@<time>" (toItem). */
fun notificationKeyOf(sourceKey: String) = sourceKey.substringBeforeLast('@')

internal fun Posted.toItem() = HubItem(
    HubAccounts.NOTIFICATIONS.id, "$key@${if (whenTime > 0) whenTime else postTime}", ItemKind.NOTIFICATION, postTime,
    lastSender.orNull() ?: title.orEmpty(),
    subject = conversationTitle.orNull(), preview = lastMessage ?: bigText ?: text.orEmpty(),
    folder = packageName, conversationId = key, category = category.orEmpty(), messaging = messaging,
)

/**
 * [fresh] as its source reads it again, with what the Hub holds of it ([stored]): the Hub's read
 * state while the source's own has not changed, and its deletion. A new item is the source's.
 */
internal fun held(fresh: HubItem, stored: HubItem?): HubItem = when (stored) {
    null -> fresh
    else -> fresh.copy(read = if (fresh.sourceRead != stored.sourceRead) fresh.read else stored.read, deleted = stored.deleted)
}

private fun HubItem.sameContent(other: HubItem) =
    sender == other.sender && subject == other.subject && preview == other.preview

/**
 * Fills [sink] from the providers' [rows] and the posted notifications. Not thread safe: one
 * caller at a time (apps/hub's sources thread). Each method returns how many items it wrote.
 */
class HubSources(private val sink: HubSink, private val rows: ProviderRows, private val now: () -> Long) {
    private val accountsPut = HashSet<String>()

    /** The latest item of each notification still posted, against reposts without anything new. */
    private val last = HashMap<String, HubItem>()

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
        if (emptyRead) return 0
        val stored = sink.items(account.id).associateBy { it.sourceKey }
        val put = fresh.map { held(adjust(it, stored[it.sourceKey]), stored[it.sourceKey]) }.filter { stored[it.sourceKey]?.copy(addedAt = 0) != it }
        val gone = stored.keys - fresh.mapTo(HashSet()) { it.sourceKey }
        if (put.isNotEmpty()) sink.put(put)
        if (gone.isNotEmpty()) sink.remove(account.id, gone)
        return put.size + gone.size
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

    /** Writes [posted] if it enters the Hub and shows something new. */
    suspend fun posted(posted: Posted): Int {
        if (!posted.entersHub()) return 0
        val item = posted.toItem()
        if (last[posted.key]?.sameContent(item) == true) return 0
        account(HubAccounts.NOTIFICATIONS)
        // The same message updated (its time kept): what the Hub set on it stays, as in sync.
        sink.put(listOf(held(item, sink.item(item.accountId, item.sourceKey))))
        last[posted.key] = item
        return 1
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
        val keys = active.mapTo(HashSet()) { it.key }
        last.keys.retainAll(keys)
        val stored = sink.items(HubAccounts.NOTIFICATIONS.id)
        // The latest item of each notification still posted, which a new process does not remember.
        // Not one its source read (removed): the same content posted again is a new item. One
        // read in the Hub alone is still posted, and its reposts are nothing new.
        stored.filter { !it.sourceRead && it.conversationId in keys }.sortedBy { it.timestamp }.associateBy { it.conversationId!! }
            .forEach { (key, item) -> last.putIfAbsent(key, item) }
        var written = active.sumOf { posted(it) }
        if (complete) {
            // Not removed yet only: the trace grows without limit, what is still posted does not.
            val gone = stored.filter { !it.sourceRead && it.conversationId != null && it.conversationId !in keys }.mapTo(HashSet()) { it.conversationId!! }
            if (gone.isNotEmpty()) written += sink.markRead(HubAccounts.NOTIFICATIONS.id, gone)
        }
        return written
    }
}
