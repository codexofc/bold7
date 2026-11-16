package dev.bold7.mail

import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.store.imap.FolderListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class SyncRulesTest {
    private val unread = MailFlags(read = false, flagged = false, answered = false)
    private val read = unread.copy(read = true)

    @Test
    fun `first sync fetches the whole window, newest first`() {
        val plan = planSync(local = null, remote = RemoteFolder(7, mapOf(10L to unread, 12L to read, 11L to unread)), stillOnServer = emptySet())
        assertEquals(SyncPlan(fetch = listOf(12L, 11L, 10L), remove = emptyList(), flagChanges = emptyMap()), plan)
    }

    @Test
    fun `new messages are fetched, known ones are not`() {
        val local = FolderState(7, mapOf(10L to unread, 11L to unread))
        val plan = planSync(local, RemoteFolder(7, mapOf(10L to unread, 11L to unread, 13L to unread)), emptySet())
        assertEquals(listOf(13L), plan.fetch)
        assertEquals(emptyList(), plan.remove)
        assertEquals(emptyMap(), plan.flagChanges)
    }

    @Test
    fun `a flag changed elsewhere is carried over`() {
        val local = FolderState(7, mapOf(10L to unread, 11L to unread))
        val plan = planSync(local, RemoteFolder(7, mapOf(10L to read, 11L to unread)), emptySet())
        assertEquals(mapOf(10L to read), plan.flagChanges)
    }

    @Test
    fun `known messages outside the window are the ones to ask the server about`() {
        val local = FolderState(7, mapOf(3L to unread, 10L to unread, 11L to unread))
        assertEquals(listOf(3L, 11L), outsideWindow(local, RemoteFolder(7, mapOf(10L to unread, 14L to unread))))
        assertEquals(emptyList(), outsideWindow(local, RemoteFolder(8, emptyMap())), "a new UIDVALIDITY drops them all anyway")
        assertEquals(emptyList(), outsideWindow(null, RemoteFolder(7, emptyMap())))
    }

    @Test
    fun `a message gone from inside the window was deleted or moved on the server`() {
        val local = FolderState(7, mapOf(10L to unread, 11L to unread, 12L to unread))
        val plan = planSync(local, RemoteFolder(7, mapOf(10L to unread, 12L to unread)), stillOnServer = emptySet())
        assertEquals(listOf(11L), plan.remove)
    }

    @Test
    fun `an old message stays while the server has it, and goes once deleted there`() {
        // 3 aged out of the window and is still on the server; 2, the oldest, was deleted on the web.
        val local = FolderState(7, mapOf(2L to unread, 3L to unread, 10L to unread))
        val plan = planSync(local, RemoteFolder(7, mapOf(10L to unread, 14L to unread)), stillOnServer = setOf(3L))
        assertEquals(listOf(2L), plan.remove)
        assertEquals(listOf(14L), plan.fetch)
    }

    @Test
    fun `with an empty window, only what the server still has stays`() {
        val local = FolderState(7, mapOf(3L to unread, 4L to read))
        assertEquals(
            SyncPlan(emptyList(), listOf(3L), emptyMap()),
            planSync(local, RemoteFolder(7, emptyMap()), stillOnServer = setOf(4L)),
        )
    }

    @Test
    fun `a new UIDVALIDITY invalidates every known UID`() {
        val local = FolderState(7, mapOf(10L to unread, 11L to read))
        val plan = planSync(local, RemoteFolder(8, mapOf(10L to unread, 20L to unread)), stillOnServer = setOf(10L, 11L))
        assertEquals(listOf(20L, 10L), plan.fetch)
        assertEquals(listOf(10L, 11L), plan.remove)
        assertEquals(emptyMap(), plan.flagChanges)
    }

    @Test
    fun `the Hub syncs the inbox, sent and drafts, and pushes the inbox only`() {
        val gmail = listOf(
            FolderListItem("INBOX", "INBOX", FolderType.INBOX),
            FolderListItem("[Gmail]/All Mail", "All Mail", FolderType.ARCHIVE),
            FolderListItem("[Gmail]/Drafts", "Drafts", FolderType.DRAFTS),
            FolderListItem("[Gmail]/Sent Mail", "Sent Mail", FolderType.SENT),
            FolderListItem("[Gmail]/Spam", "Spam", FolderType.SPAM),
            FolderListItem("[Gmail]/Starred", "Starred", FolderType.REGULAR),
            FolderListItem("[Gmail]/Trash", "Trash", FolderType.TRASH),
            FolderListItem("Factures", "Factures", FolderType.REGULAR),
        )
        assertEquals(
            listOf(
                SyncedFolder("INBOX", FolderRole.INBOX),
                SyncedFolder("[Gmail]/Sent Mail", FolderRole.SENT),
                SyncedFolder("[Gmail]/Drafts", FolderRole.DRAFTS),
            ),
            planFolders(gmail),
        )
    }

    @Test
    fun `a server without special-use attributes gets its inbox synced, nothing else`() {
        val plain = listOf(
            FolderListItem("INBOX", "INBOX", FolderType.INBOX),
            FolderListItem("Sent", "Sent", FolderType.REGULAR),
        )
        assertEquals(listOf(SyncedFolder("INBOX", FolderRole.INBOX)), planFolders(plain))
    }

    @Test
    fun `IDLE is renewed every 15 minutes`() {
        assertEquals(15.minutes, PushPolicy.IDLE_REFRESH)
    }

    @Test
    fun `after a failure, retries back off and stay bounded`() {
        assertEquals(
            listOf(1, 2, 4, 8, 16, 30, 30).map { it.minutes },
            (1..7).map { PushPolicy.retryDelay(failures = it) },
        )
    }

    @Test
    fun `push wakes the phone at most a handful of times an hour without mail`() {
        // Worst case without mail: IDLE renewals while connected, or retries once backed off.
        assertEquals(4.0, 60.minutes / PushPolicy.IDLE_REFRESH)
        assertEquals(2.0, 60.minutes / PushPolicy.retryDelay(failures = 100))
    }
}
