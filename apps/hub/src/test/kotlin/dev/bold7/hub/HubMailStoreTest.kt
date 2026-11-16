package dev.bold7.hub

import dev.bold7.hub.data.Account
import dev.bold7.hub.data.Attachment
import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.HubSink
import dev.bold7.mail.FolderRole
import dev.bold7.mail.FolderState
import dev.bold7.mail.MailAddress
import dev.bold7.mail.MailFlags
import dev.bold7.mail.MailItem
import dev.bold7.mail.RemoteFolder
import dev.bold7.mail.SyncedFolder
import dev.bold7.mail.planSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubMailStoreTest {
    private class Sink : HubSink {
        val items = linkedMapOf<String, HubItem>()
        var writes = 0
        override suspend fun putAccount(account: Account) = Unit
        override suspend fun put(items: List<HubItem>) = items.forEach { this.items[it.sourceKey] = it }
        override suspend fun putAttachments(accountId: String, sourceKey: String, attachments: List<Attachment>) = Unit
        override suspend fun item(accountId: String, sourceKey: String) = items[sourceKey]
        override suspend fun items(accountId: String) = items.values.filter { it.accountId == accountId }
        override suspend fun markRead(accountId: String, conversationIds: Collection<String>) = 0
        override suspend fun remove(accountId: String, sourceKeys: Collection<String>) = sourceKeys.forEach { items.remove(it) }
        override suspend fun replace(accountId: String, remove: Collection<String>, put: List<HubItem>) {
            writes++
            remove(accountId, remove)
            put(put)
        }
        override suspend fun removeAccount(accountId: String) = Unit
    }

    private val sink = Sink()
    private val store = HubMailStore(sink, "mail-1")
    private val inbox = SyncedFolder("INBOX", FolderRole.INBOX)
    private val sent = SyncedFolder("[Gmail]/Sent|Mail", FolderRole.SENT)
    private val unread = MailFlags(read = false, flagged = false, answered = false)

    private fun mail(uid: Long, folder: SyncedFolder = inbox, flags: MailFlags = unread) = MailItem(
        "alice@example.org", folder.serverId, folder.role, uid, "<$uid@x>", "<1@x>",
        MailAddress("Alice Martin", "alice.martin@example.org"), listOf(MailAddress(null, "bob@example.org")), "Objet $uid", 1000L * uid, flags, hasAttachment = uid == 2L,
    )

    /** One sync of [folder] as Mailbox runs it, against the server's [remote] flags. */
    private fun sync(folder: SyncedFolder, validity: Long, remote: Map<Long, MailFlags>) {
        val plan = planSync(store.folderState(folder.serverId), RemoteFolder(validity, remote), emptySet())
        store.apply(folder, validity, plan, plan.fetch.map { mail(it, folder, remote.getValue(it)) })
    }

    @Test
    fun `a key names its folder, whatever it holds, its UIDVALIDITY and its UID`() {
        val key = MailKey("[Gmail]/Sent|Mail", 7, 42)
        assertEquals(key, MailKey.parse(key.toString()))
        assertNull(MailKey.parse("sms:12"))
    }

    @Test
    fun `received mail shows its sender, sent mail its first recipient, by subject`() {
        val received = mail(2).toHubItem("mail-1", 7)
        assertEquals("Alice Martin", received.sender)
        assertEquals("alice.martin@example.org", received.address)
        assertEquals("Objet 2", received.subject)
        assertEquals(Box.INBOX, received.box)
        assertEquals(1, received.attachmentCount)
        assertEquals("<1@x>", received.conversationId)
        val out = mail(3, sent).toHubItem("mail-1", 7)
        assertEquals("bob@example.org", out.sender)
        assertEquals(Box.SENT, out.box)
    }

    @Test
    fun `the Hub follows the server, and its own read state survives a sync`() {
        sync(inbox, 7, mapOf(1L to unread, 2L to unread))
        assertEquals(FolderState(7, mapOf(1L to unread, 2L to unread)), store.folderState("INBOX"))

        // Marked read in Messages only (the server not told yet): a sync that changes nothing keeps it.
        val key = MailKey("INBOX", 7, 1).toString()
        sink.items[key] = sink.items.getValue(key).copy(read = true)
        val before = sink.writes
        sync(inbox, 7, mapOf(1L to unread, 2L to unread))
        assertTrue(sink.items.getValue(key).read)
        assertEquals(before, sink.writes, "nothing written when nothing changed")

        // Read on the web: the source changed, the Hub follows; 2 deleted there.
        sync(inbox, 7, mapOf(1L to unread.copy(read = true)))
        assertEquals(setOf(key), sink.items.keys)
        assertTrue(sink.items.getValue(key).sourceRead)

        // Unread again on the web: the Hub follows that too.
        sync(inbox, 7, mapOf(1L to unread))
        assertEquals(false, sink.items.getValue(key).read)
    }

    @Test
    fun `answered alone writes nothing`() {
        sync(inbox, 7, mapOf(1L to unread))
        val before = sink.writes
        sync(inbox, 7, mapOf(1L to unread.copy(answered = true)))
        assertEquals(before, sink.writes)
    }

    @Test
    fun `a new UIDVALIDITY replaces the folder, the other folders stay`() {
        sync(inbox, 7, mapOf(1L to unread))
        sync(sent, 3, mapOf(9L to unread))
        sync(inbox, 8, mapOf(1L to unread, 5L to unread))
        assertEquals(setOf("INBOX|8|1", "INBOX|8|5", "[Gmail]/Sent|Mail|3|9"), sink.items.keys)
    }

    @Test
    fun `a sync applied twice changes nothing more`() {
        val remote = mapOf(1L to unread)
        val plan = planSync(null, RemoteFolder(7, remote), emptySet())
        store.apply(inbox, 7, plan, listOf(mail(1)))
        val once = sink.items.toMap()
        store.apply(inbox, 7, plan, listOf(mail(1)))
        assertEquals(once, sink.items.toMap())
    }
}
