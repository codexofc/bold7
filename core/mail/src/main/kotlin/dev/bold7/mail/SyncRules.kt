package dev.bold7.mail

import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.store.imap.FolderListItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class MailFlags(val read: Boolean, val flagged: Boolean, val answered: Boolean)

/** What the Hub already holds for a folder. */
data class FolderState(val uidValidity: Long, val flags: Map<Long, MailFlags>)

/** UIDs and flags of the messages in the sync window on the server. */
data class RemoteFolder(val uidValidity: Long, val flags: Map<Long, MailFlags>)

data class SyncPlan(val fetch: List<Long>, val remove: List<Long>, val flagChanges: Map<Long, MailFlags>)

/**
 * Known UIDs outside the window: aged out of it, or deleted or moved on the server. The server
 * tells which ones it still has (UID SEARCH UID), the answer goes to [planSync]. After a new
 * UIDVALIDITY every known UID goes anyway: nothing to ask.
 */
fun outsideWindow(local: FolderState?, remote: RemoteFolder): List<Long> =
    if (local == null || local.uidValidity != remote.uidValidity) emptyList() else (local.flags.keys - remote.flags.keys).sorted()

/**
 * Headers to fetch (newest first, so the Hub fills from the top), items to remove, flags to
 * update. A known UID outside the window stays only if the server still has it ([stillOnServer],
 * the answer for [outsideWindow]). A new UIDVALIDITY voids every known UID (RFC 3501, 2.3.1.1).
 */
fun planSync(local: FolderState?, remote: RemoteFolder, stillOnServer: Set<Long>): SyncPlan {
    if (local == null || local.uidValidity != remote.uidValidity) {
        return SyncPlan(
            fetch = remote.flags.keys.sortedDescending(),
            remove = local?.flags?.keys?.sorted().orEmpty(),
            flagChanges = emptyMap(),
        )
    }
    return SyncPlan(
        fetch = (remote.flags.keys - local.flags.keys).sortedDescending(),
        remove = local.flags.keys.filter { it !in remote.flags && it !in stillOnServer }.sorted(),
        flagChanges = remote.flags.filter { (uid, flags) -> local.flags[uid].let { it != null && it != flags } },
    )
}

enum class FolderRole { INBOX, SENT, DRAFTS }

data class SyncedFolder(val serverId: String, val role: FolderRole)

/**
 * The folders the Hub shows: inbox, sent and drafts, found by their special-use attribute
 * (RFC 6154). Gmail's other folders (All Mail, Starred, labels) would repeat the same messages.
 * Simplification: no guess by folder name; extend it when a server without SPECIAL-USE needs it.
 */
fun planFolders(folders: List<FolderListItem>): List<SyncedFolder> = listOfNotNull(
    folders.firstOrNull { it.type == FolderType.INBOX }?.let { SyncedFolder(it.serverId, FolderRole.INBOX) },
    folders.firstOrNull { it.type == FolderType.SENT }?.let { SyncedFolder(it.serverId, FolderRole.SENT) },
    folders.firstOrNull { it.type == FolderType.DRAFTS }?.let { SyncedFolder(it.serverId, FolderRole.DRAFTS) },
)

/** Bounds of the background cost of push. */
object PushPolicy {
    /** IDLE renewal every 15 minutes; also keeps NAT entries alive. */
    val IDLE_REFRESH: Duration = 15.minutes

    /** Messages of the last days in the header window. */
    const val WINDOW_DAYS = 7

    /** And at most this many, whatever the date. */
    const val WINDOW_MAX = 500

    private val MAX_RETRY = 30.minutes

    /** Wait before reconnecting after the [failures]-th failure in a row: 1, 2, 4… minutes, at most 30. */
    fun retryDelay(failures: Int): Duration =
        minOf(1.minutes * (1L shl (failures - 1).coerceIn(0, 10)).toInt(), MAX_RETRY)
}
