package dev.bold7.hub

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.HubSink
import dev.bold7.hub.data.ItemKind
import dev.bold7.mail.FolderRole
import dev.bold7.mail.FolderState
import dev.bold7.mail.MailFlags
import dev.bold7.mail.MailItem
import dev.bold7.mail.MailStore
import dev.bold7.mail.SyncPlan
import dev.bold7.mail.SyncedFolder
import kotlinx.coroutines.runBlocking

// core/mail's sync written into the Hub's store. Pure but for the
// sink: tested against a fake one.

/** Where a mail item sits on its server: its folder, the folder's UIDVALIDITY, its UID. */
data class MailKey(val folder: String, val uidValidity: Long, val uid: Long) {
    /** The item's sourceKey: stable while the folder's UIDVALIDITY stays (then the sync reloads it). */
    override fun toString() = "$folder|$uidValidity|$uid"

    companion object {
        /** From the end: a folder's name may hold the separator. Null: not a mail item's key. */
        fun parse(sourceKey: String): MailKey? {
            val last = sourceKey.lastIndexOf('|')
            val middle = if (last > 0) sourceKey.lastIndexOf('|', last - 1) else -1
            if (middle <= 0) return null
            val uid = sourceKey.substring(last + 1).toLongOrNull() ?: return null
            val validity = sourceKey.substring(middle + 1, last).toLongOrNull() ?: return null
            return MailKey(sourceKey.substring(0, middle), validity, uid)
        }
    }
}

/**
 * [item] as the Hub's item of account [accountId]. A received mail shows its sender; a sent one
 * or a draft its first recipient. No preview: no body is read at
 * the sync, the row shows the subject (gsm 027, C).
 */
fun MailItem.toHubItem(accountId: String, uidValidity: Long): HubItem {
    val who = if (role == FolderRole.INBOX) from else to.firstOrNull()
    return HubItem(
        accountId = accountId,
        sourceKey = MailKey(folder, uidValidity, uid).toString(),
        kind = ItemKind.EMAIL,
        timestamp = receivedAt,
        sender = who?.name ?: who?.email.orEmpty(),
        address = who?.email,
        subject = subject,
        box = when (role) {
            FolderRole.INBOX -> Box.INBOX
            FolderRole.SENT -> Box.SENT
            FolderRole.DRAFTS -> Box.DRAFT
        },
        folder = folder,
        conversationId = thread,
        read = flags.read,
        flagged = flags.flagged,
        attachmentCount = if (hasAttachment) 1 else 0,
        sourceRead = flags.read,
    )
}

/**
 * [fresh] over what the Hub holds ([stored]): the server's read state rewrites the Hub's only when
 * it changed (HubItem.sourceRead), so that Mark Read or Unread in Messages survives a sync; the
 * Hub's deletion stays (D7).
 */
fun merged(stored: HubItem?, fresh: HubItem): HubItem = when {
    stored == null -> fresh
    else -> fresh.copy(read = if (stored.sourceRead == fresh.sourceRead) stored.read else fresh.read, deleted = stored.deleted)
}

/**
 * core/mail's [MailStore] on the Hub's [sink], for mail account [accountId]. Blocking: called by
 * the sync on its own thread, never the main one.
 */
class HubMailStore(private val sink: HubSink, private val accountId: String) : MailStore {
    private fun stored(folder: String): List<Pair<MailKey, HubItem>> = runBlocking { sink.items(accountId) }
        .mapNotNull { item -> MailKey.parse(item.sourceKey)?.takeIf { it.folder == folder && item.kind == ItemKind.EMAIL }?.let { it to item } }

    // Simplification: answered is not kept by the Hub, so planSync lists an answered mail as changed
    // at each sync; apply writes nothing for it. Add a column if the Hub ever draws "replied".
    override fun folderState(folder: String): FolderState? {
        val items = stored(folder)
        if (items.isEmpty()) return null
        val validity = items.maxOf { it.first.uidValidity }
        return FolderState(
            validity,
            items.filter { it.first.uidValidity == validity }.associate { (key, item) -> key.uid to MailFlags(read = item.sourceRead, flagged = item.flagged, answered = false) },
        )
    }

    override fun apply(folder: SyncedFolder, uidValidity: Long, plan: SyncPlan, added: List<MailItem>) {
        val stored = stored(folder.serverId)
        val current = stored.filter { it.first.uidValidity == uidValidity }.associate { it.first.uid to it.second }
        // A new UIDVALIDITY removes every UID of the old one, under its old key.
        val remove = stored.filter { it.first.uid in plan.remove }.map { it.second.sourceKey }
        val changed = plan.flagChanges.mapNotNull { (uid, flags) ->
            val item = current[uid] ?: return@mapNotNull null
            if (item.sourceRead == flags.read && item.flagged == flags.flagged) return@mapNotNull null
            item.copy(read = if (item.sourceRead != flags.read) flags.read else item.read, sourceRead = flags.read, flagged = flags.flagged)
        }
        val put = changed + added.map { merged(current[it.uid], it.toHubItem(accountId, uidValidity)) }
        if (remove.isEmpty() && put.isEmpty()) return
        runBlocking { sink.replace(accountId, remove, put) }
    }
}
