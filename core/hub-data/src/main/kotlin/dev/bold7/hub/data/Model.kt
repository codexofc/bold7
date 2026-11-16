package dev.bold7.hub.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// The Hub's model, stored as is by Room. Enums are stored by name:
// a new value (BBM, a social network) needs no migration, renaming one does.

/** One line of the Hub's accounts: Text Messages, Calls, Notifications, a mailbox. */
@Entity(tableName = "account")
data class Account(
    @PrimaryKey val id: String,
    val kind: AccountKind,
    val name: String,
    /** Under the name: the mail address, or nothing. */
    val address: String? = null,
    val color: AccountColor = AccountColor.NONE,
    /** Order in the accounts list, smallest first. */
    val position: Int = 0,
    /** Epoch ms of the last time this account was seen in the Hub: unread items after it are new (the splat). */
    val seenAt: Long = 0,
)

/** CALENDAR holds the invitations until a mail account carries them, until then. */
enum class AccountKind { TEXT_MESSAGES, CALLS, NOTIFICATIONS, EMAIL, CALENDAR }

/** The account's bar at the left of its lines, NONE by default. */
enum class AccountColor { NONE, GREEN, BLUE, ORANGE, PINK, PURPLE }

/**
 * One element of the Hub. Its key is the account and the source's own key (an SMS's _id, a
 * notification's key, a call log id, a mail's UID), so a source writes the same element again
 * without duplicating it.
 */
@Entity(
    tableName = "item",
    primaryKeys = ["accountId", "sourceKey"],
    foreignKeys = [ForeignKey(Account::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("timestamp")],
)
data class HubItem(
    val accountId: String,
    val sourceKey: String,
    val kind: ItemKind,
    val timestamp: Long,
    val sender: String,
    /** Phone number or mail address of the sender, for contacts and Priority Hub rules later. */
    val address: String? = null,
    val subject: String? = null,
    val preview: String = "",
    val box: Box = Box.INBOX,
    /**
     * The source's folder, for the folder filter: a mail folder, or the package of the app that
     * posted a notification; null otherwise.
     */
    val folder: String? = null,
    /** Items sharing it within an account are one conversation (SMS thread, mail thread). */
    val conversationId: String? = null,
    val read: Boolean = false,
    val flagged: Boolean = false,
    val importance: Importance = Importance.NORMAL,
    /** A level 1 message: red text, its own sound. */
    val level1: Boolean = false,
    /** As the source knows it, even before the attachments' details are in [Attachment]. */
    val attachmentCount: Int = 0,
    /**
     * Epoch ms of its arrival in the Hub, stamped by the store at the first write and kept by the
     * next ones: what makes it new (Counts), whatever its own [timestamp] says.
     */
    val addedAt: Long = 0,
    /**
     * [read] as the source last said it (the SMS provider, the call log, a notification gone).
     * [read] is the Hub's own: a source rewrites it only when its own state
     * changes, so marking an item read or unread in the Hub survives the source's next read.
     * Version 2 of the database.
     */
    @ColumnInfo(defaultValue = "0") val sourceRead: Boolean = false,
    /**
     * Deleted in the Hub: left out of every view and counter, but kept so that its source,
     * which still has it, does not bring it back. Version 2.
     */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /**
     * A notification's Notification.category: "" when it has none, null when unknown (a row older
     * than version 3, and every item that is no notification). Version 3.
     */
    val category: String? = null,
    /** A notification in MessagingStyle. Version 3. */
    @ColumnInfo(defaultValue = "0") val messaging: Boolean = false,
)

/** An item, as the launcher names it to the provider (HubContract's calls). */
data class ItemKey(val accountId: String, val sourceKey: String)

enum class ItemKind { SMS, MMS, EMAIL, MISSED_CALL, NOTIFICATION, INVITATION }

/** Where the item sits for its owner: received, sent, draft, waiting to leave. */
enum class Box { INBOX, SENT, DRAFT, OUTBOX }

/** The mail's importance (`ii_content_priority_high/low`). */
enum class Importance { LOW, NORMAL, HIGH }

@Entity(
    tableName = "attachment",
    primaryKeys = ["accountId", "sourceKey", "position"],
    foreignKeys = [
        ForeignKey(HubItem::class, ["accountId", "sourceKey"], ["accountId", "sourceKey"], onDelete = ForeignKey.CASCADE),
    ],
)
data class Attachment(
    val accountId: String,
    val sourceKey: String,
    val position: Int,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
)
