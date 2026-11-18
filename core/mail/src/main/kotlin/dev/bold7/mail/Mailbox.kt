package dev.bold7.mail

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.DefaultBodyFactory
import com.fsck.k9.mail.FetchProfile
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import com.fsck.k9.mail.power.WakeLock
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mail.store.imap.IdleRefreshManager
import com.fsck.k9.mail.store.imap.IdleRefreshTimeoutProvider
import com.fsck.k9.mail.store.imap.ImapClientInfo
import com.fsck.k9.mail.store.imap.ImapFolder
import com.fsck.k9.mail.store.imap.ImapFolderIdler
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.store.imap.NegativeImapResponseException
import com.fsck.k9.mail.store.imap.OpenMode
import com.fsck.k9.mail.store.imap.RealImapFolder
import com.fsck.k9.mail.transport.smtp.SmtpTransport
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag

/**
 * The Hub's side of the sync: what it holds per folder, and where the result goes. core/hub-data's
 * adapter implements it; tests use an in-memory fake.
 */
interface MailStore {
    fun folderState(folder: String): FolderState?

    /**
     * Applies [plan] with the headers of the fetched messages ([added]), in one transaction.
     * Must be idempotent: push may sync a folder again while a sync of it is ending (the same
     * removal or the same added UID twice), and a failed apply is retried at the next sync.
     */
    fun apply(folder: SyncedFolder, uidValidity: Long, plan: SyncPlan, added: List<MailItem>)
}

/**
 * One account's IMAP and SMTP, on Thunderbird's protocol code. Every call blocks on the network:
 * call it from the io dispatcher, never from the main thread. Nothing connects before the first
 * call, and a [Mailbox] only exists for an account the user entered ([AccountVault.load]).
 */
class Mailbox(
    private val account: MailAccount,
    private val store: MailStore,
    tempDir: File,
    private val sockets: TrustedSocketFactory = TlsSocketFactory(pins = account.pins),
    private val now: () -> Long = System::currentTimeMillis,
    /** The access tokens of an account signed in through the browser, null for an app password. */
    private val oauthTokens: OAuth2TokenProvider? = null,
) {
    init {
        require(account.oauth == null || oauthTokens != null) { "An OAuth account needs its tokens" }
        // Where the MIME parser spills large parts (Thunderbird keeps this global).
        BinaryTempFileBody.setTempDirectory(tempDir)
    }

    private val imap = ImapStore.create(account.imapSettings(), Config, sockets, oauthTokenProvider = oauthTokens)

    fun folders(): List<SyncedFolder> = planFolders(imap.getFolders())

    /** Headers and flags of the window ([PushPolicy.WINDOW_DAYS]), into [store]. No body is fetched. */
    suspend fun sync(folder: SyncedFolder): SyncPlan = imap.getFolder(folder.serverId).openReadOnly { remote ->
        val uidValidity = remote.getUidValidity() ?: throw MessagingException("No UIDVALIDITY for ${folder.serverId}")
        val count = remote.messageCount
        val messages = if (count <= 0) {
            emptyList()
        } else {
            val since = Date(now() - TimeUnit.DAYS.toMillis(PushPolicy.WINDOW_DAYS.toLong()))
            remote.getMessages(maxOf(1, count - PushPolicy.WINDOW_MAX + 1), count, since, null)
        }
        if (messages.isNotEmpty()) remote.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.FLAGS) }, null, 0)

        val local = store.folderState(folder.serverId)
        val window = RemoteFolder(uidValidity, messages.associate { it.uid.toLong() to it.flags() })
        val outside = outsideWindow(local, window)
        // Simplification: getMessagesFromUids is internal to RealImapFolder, reachable from this module only.
        val stillOnServer = if (outside.isEmpty()) {
            emptySet()
        } else {
            (remote as RealImapFolder).getMessagesFromUids(outside.map(Long::toString)).map { it.uid.toLong() }.toSet()
        }
        val plan = planSync(local, window, stillOnServer)
        val fetched = plan.fetch.map { remote.getMessage(it.toString()) }
        if (fetched.isNotEmpty()) {
            // Two FETCH: parsing the header literal of a combined one drops the parsed BODYSTRUCTURE.
            remote.fetch(fetched, HEADERS, null, 0)
            remote.fetch(fetched, STRUCTURE, null, 0)
        }
        store.apply(folder, uidValidity, plan, fetched.map { it.toMailItem(account.address, folder, it.uid.toLong()) })
        plan
    }

    /** Text and HTML of one message, fetched when it is opened. Attachments stay on the server. */
    suspend fun body(folder: SyncedFolder, uid: Long): MailBody = imap.getFolder(folder.serverId).openReadOnly { remote ->
        val message = remote.getMessage(uid.toString())
        remote.fetch(listOf(message), STRUCTURE, null, 0)
        for (part in message.readableParts()) remote.fetchPart(message, part, DefaultBodyFactory(), MAX_TEXT_BYTES)
        message.readableBody()
    }

    /**
     * A message as Messages opens it: its headers, then its text as [body] reads it. Read-only
     * too: opening it does not mark it read on the server ([setRead] does, when Messages asks).
     */
    suspend fun open(folder: String, uid: Long): OpenedMail = imap.getFolder(folder).openReadOnly { remote ->
        val message = remote.getMessage(uid.toString())
        // Two FETCH, as in sync: the header literal's parsing drops a structure read with it.
        remote.fetch(listOf(message), HEADERS, null, 0)
        remote.fetch(listOf(message), STRUCTURE, null, 0)
        for (part in message.readableParts()) remote.fetchPart(message, part, DefaultBodyFactory(), MAX_TEXT_BYTES)
        message.toOpenedMail(message.readableBody())
    }

    /** Marks [uids] read or unread on the server (\Seen): what Mark Read, Mark Unread and opening do in Messages. */
    fun setRead(folder: String, uids: Collection<Long>, read: Boolean) = imap.getFolder(folder).openReadWrite { remote ->
        remote.setFlags(uids.map { remote.getMessage(it.toString()) }, setOf(Flag.SEEN), read)
    }

    /** The server's trash (special-use \Trash), where Delete moves a message; null: none. */
    fun trash(): String? = imap.getFolders().firstOrNull { it.type == FolderType.TRASH }?.serverId

    /**
     * Delete in Messages: [uids] moved into [trash] (Gmail keeps them 30 days there), never erased.
     * Without a trash, or from the trash itself, nothing is done on the server: false.
     */
    fun delete(folder: String, uids: Collection<Long>, trash: String?): Boolean {
        if (trash == null || trash == folder || uids.isEmpty()) return false
        imap.getFolder(folder).openReadWrite { remote ->
            remote.moveMessages(uids.map { remote.getMessage(it.toString()) }, imap.getFolder(trash))
        }
        return true
    }

    /** Sends over SMTP. Gmail files the copy in Sent itself (no IMAP APPEND here). */
    fun send(message: Message) = SmtpTransport(account.smtpSettings(), sockets, oauthTokenProvider = oauthTokens).sendMessage(message)

    fun idler(folder: SyncedFolder, refresh: IdleRefreshManager, wakeLock: WakeLock): ImapFolderIdler =
        ImapFolderIdler.create(refresh, wakeLock, imap, folder.serverId, IdleTimeout)

    fun close() = imap.closeAllConnections()

    /**
     * Logs in to both servers, as the account's setup checks it: throws what refuses it. An IMAP
     * server that answers NO or BAD with a response code other than AUTHENTICATIONFAILED (Gmail's
     * [ALERT] for a Google password, its refusals after a few wrong ones) still refused the account:
     * it is reported as a refused password, with the server's words, never as the network.
     */
    fun check() {
        checkImap()
        checkSmtp()
    }

    /** [check], its failure described: which server, and why; null when both logged in. */
    fun checkError(): CheckError? {
        for ((server, step) in listOf(account.provider.imap to ::checkImap, account.provider.smtp to ::checkSmtp)) {
            try {
                step()
            } catch (e: Exception) {
                return diagnose(e, server, now())
            }
        }
        return null
    }

    private fun checkImap() {
        try {
            imap.checkSettings()
        } catch (e: NegativeImapResponseException) {
            throw AuthenticationFailedException("Refused by the server", e, e.alertText ?: e.responseText)
        }
    }

    private fun checkSmtp() = SmtpTransport(account.smtpSettings(), sockets, oauthTokenProvider = oauthTokens).checkSettings()

    private inline fun <T> ImapFolder.openReadOnly(block: (ImapFolder) -> T): T {
        open(OpenMode.READ_ONLY)
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private inline fun <T> ImapFolder.openReadWrite(block: (ImapFolder) -> T): T {
        open(OpenMode.READ_WRITE)
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private object Config : ImapStoreConfig {
        override val logLabel = "Bold7"
        override fun isSubscribedFoldersOnly() = false
        override fun isExpungeImmediately() = false
        override fun clientInfo() = ImapClientInfo(appName = "Bold7", appVersion = "0.1")
    }

    private object IdleTimeout : IdleRefreshTimeoutProvider {
        override val idleRefreshTimeoutMs = PushPolicy.IDLE_REFRESH.inWholeMilliseconds
    }

    private companion object {
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        val HEADERS = FetchProfile().apply {
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.ENVELOPE)
        }
        val STRUCTURE = FetchProfile().apply { add(FetchProfile.Item.STRUCTURE) }
    }
}
