package dev.bold7.mail

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.CertificateValidationException
import com.fsck.k9.mail.K9MailLib
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.TextBody
import com.fsck.k9.mail.power.WakeLock
import com.fsck.k9.mail.store.imap.IdleRefreshManager
import com.fsck.k9.mail.store.imap.IdleRefreshTimer
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Flags
import jakarta.mail.Session
import java.io.IOException
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Properties
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.schedule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.legacy.logging.Log

/** Against GreenMail, an IMAP (IDLE included) and SMTP server in this JVM, on localhost. */
class MailboxTest {
    private val address = "alice@gmail.com"
    private val password = "abcd efgh ijkl mnop"
    private val greenMail = GreenMail(ServerSetupTest.SMTP_IMAP.map { it.dynamicPort() }.toTypedArray())
    private val inbox = SyncedFolder("INBOX", FolderRole.INBOX)
    private val store = FakeMailStore()
    private val tempDir = Files.createTempDirectory("mail").toFile()

    @BeforeTest
    fun start() {
        greenMail.start()
        greenMail.setUser(address, address, password)
    }

    @AfterTest
    fun stop() {
        greenMail.stop()
        Log.sink = null
        K9MailLib.setDebug(false)
    }

    private fun account(password: String = this.password) = MailAccount(
        address,
        password,
        MailProvider(
            "test",
            Endpoint("127.0.0.1", greenMail.imap.port, tls = false),
            Endpoint("127.0.0.1", greenMail.smtp.port, tls = false),
        ),
    )

    private fun mailbox(account: MailAccount = account()) = Mailbox(account, store, tempDir)

    private val serverInbox get() = greenMail.managers.imapHostManager.getInbox(greenMail.managers.userManager.getUser(address))

    private fun deliver(subject: String, extra: String = "Content-Type: text/plain; charset=utf-8\r\n\r\nBonjour", date: Date = Date()) {
        val raw = "From: Renee <renee@example.org>\r\nTo: $address\r\nSubject: $subject\r\nMessage-ID: <$subject@example.org>\r\n$extra"
        serverInbox.appendMessage(jakarta.mail.internet.MimeMessage(Session.getInstance(Properties()), raw.byteInputStream()), Flags(), date)
    }

    private fun message(subject: String) = MimeMessage().apply {
        setFrom(Address(address))
        setHeader("To", "renee@example.org")
        this.subject = subject
        setSentDate(Date(), false)
        MimeMessageHelper.setBody(this, TextBody("Merci"))
    }

    private val attachment = """
        Content-Type: multipart/mixed; boundary=XX

        --XX
        Content-Type: text/plain; charset=utf-8

        voir pièce jointe
        --XX
        Content-Type: application/pdf; name=facture.pdf
        Content-Disposition: attachment; filename=facture.pdf
        Content-Transfer-Encoding: base64

        JVBERi0=
        --XX--
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `the Hub gets the inbox headers, then only what changed on the server`() = runBlocking<Unit> {
        deliver("premier")
        deliver("second", attachment)
        deliver("ancien", date = Date(System.currentTimeMillis() - 30L * 24 * 3600 * 1000))
        val mailbox = mailbox()

        assertEquals(listOf(inbox), mailbox.folders())
        mailbox.sync(inbox)
        val items = store.items.values.associateBy { it.subject }
        assertEquals(setOf("premier", "second"), items.keys, "the window is the last 7 days")
        assertTrue(items.getValue("second").hasAttachment)
        assertEquals(MailAddress("Renee", "renee@example.org"), items.getValue("premier").from)
        assertFalse(items.getValue("premier").flags.read)

        val first = items.getValue("premier").uid
        val second = items.getValue("second").uid
        serverInbox.setFlags(Flags(Flags.Flag.SEEN), true, first, null, false)
        serverInbox.setFlags(Flags(Flags.Flag.DELETED), true, second, null, false)
        serverInbox.expunge()
        deliver("troisième")

        val plan = mailbox.sync(inbox)
        assertEquals(listOf(second), plan.remove)
        assertEquals(setOf(first), plan.flagChanges.keys)
        assertEquals(setOf("premier", "troisième"), store.items.values.map { it.subject }.toSet())
        assertTrue(store.items.getValue(first).flags.read)
        mailbox.close()
    }

    @Test
    fun `deleting the oldest message of the window on the web is seen`() = runBlocking<Unit> {
        deliver("a")
        deliver("b")
        val mailbox = mailbox()
        mailbox.sync(inbox)
        val a = store.items.values.single { it.subject == "a" }.uid
        serverInbox.setFlags(Flags(Flags.Flag.DELETED), true, a, null, false)
        serverInbox.expunge()

        assertEquals(listOf(a), mailbox.sync(inbox).remove)
        assertEquals(setOf("b"), store.items.values.map { it.subject }.toSet())
        mailbox.close()
    }

    @Test
    fun `the window holds the 500 newest messages at most`() = runBlocking<Unit> {
        repeat(PushPolicy.WINDOW_MAX + 1) { deliver("m$it", "Content-Type: text/plain\r\n\r\nx") }
        val mailbox = mailbox()
        mailbox.sync(inbox)
        assertEquals(PushPolicy.WINDOW_MAX, store.items.size)
        assertFalse(store.items.values.any { it.subject == "m0" }, "the oldest one is left out")
        mailbox.close()
    }

    @Test
    fun `a body is fetched when asked for, text only`() = runBlocking<Unit> {
        deliver("second", attachment)
        val mailbox = mailbox()
        mailbox.sync(inbox)
        val uid = store.items.values.single().uid

        assertEquals(MailBody(text = "voir pièce jointe", html = null), mailbox.body(inbox, uid))
        mailbox.close()
    }

    @Test
    fun `reading a body leaves the message unread on the server, and the folder untouched`() = runBlocking<Unit> {
        deliver("second", attachment)
        val mailbox = mailbox()
        mailbox.sync(inbox)
        mailbox.body(inbox, store.items.values.single().uid)
        mailbox.close()

        val stored = serverInbox.messages.single()
        assertFalse(stored.isSet(Flags.Flag.SEEN), "BODY.PEEK")
        assertTrue(stored.isSet(Flags.Flag.RECENT), "EXAMINE, not SELECT: the folder is opened read-only")
    }

    @Test
    fun `push brings a new message into the Hub without asking`() = runBlocking<Unit> {
        deliver("premier")
        val refresh = TimerRefresh()
        val push = MailPush(mailbox(), inbox, refresh, NoWakeLock, Dispatchers.IO)
        val running = async(Dispatchers.IO) { push.run() }

        awaitItems(1)
        deliver("arrivé pendant IDLE")
        awaitItems(2)

        push.stop()
        assertEquals(PushEnd.STOPPED, withTimeout(10.seconds) { running.await() })
        assertEquals(0, push.failures.get())
        assertEquals(PushPolicy.IDLE_REFRESH.inWholeMilliseconds, refresh.timeouts.first(), "IDLE renewed every 15 minutes")
    }

    @Test
    fun `a failing store does not stop push, the next change syncs again`() = runBlocking<Unit> {
        deliver("a")
        val store = FakeMailStore(failFirst = true)
        val push = MailPush(Mailbox(account(), store, tempDir), inbox, TimerRefresh(), NoWakeLock, Dispatchers.IO)
        val running = async(Dispatchers.IO) { push.run() }
        withTimeout(10.seconds) { while (push.syncErrors.get() < 1) delay(50) }

        deliver("b")
        withTimeout(10.seconds) { while (store.items.size < 2) delay(50) }

        push.stop()
        assertEquals(PushEnd.STOPPED, withTimeout(10.seconds) { running.await() })
        assertEquals(1, push.syncErrors.get())
    }

    @Test
    fun `stop ends the back-off wait at once`() = runBlocking<Unit> {
        val unreachable = account()
        greenMail.stop()
        val push = MailPush(Mailbox(unreachable, store, tempDir), inbox, TimerRefresh(), NoWakeLock, Dispatchers.IO)
        val running = async(Dispatchers.IO) { push.run() }
        withTimeout(10.seconds) { while (push.failures.get() < 1) delay(50) }

        push.stop()
        assertEquals(PushEnd.STOPPED, withTimeout(5.seconds) { running.await() }, "not after the 1-minute back-off")
    }

    @Test
    fun `IDLE that keeps failing backs off further, even when syncs work`() = runBlocking<Unit> {
        deliver("a")
        val counts = CopyOnWriteArrayList<Int>()
        val push = MailPush(mailbox(), inbox, TimerRefresh(failIdle = true), NoWakeLock, Dispatchers.IO) { failures ->
            counts += failures
            50.milliseconds
        }
        val running = async(Dispatchers.IO) { push.run() }
        withTimeout(10.seconds) { while (counts.size < 5 || push.syncs.get() < 3) delay(20) }
        push.stop()
        withTimeout(10.seconds) { running.await() }

        assertEquals(listOf(1, 2, 3, 4, 5), counts.take(5), "a good sync must not reset the IDLE back-off")
    }

    @Test
    fun `a refused password ends push instead of retrying`() = runBlocking<Unit> {
        val push = MailPush(mailbox(account("faux")), inbox, TimerRefresh(), NoWakeLock, Dispatchers.IO) {
            error("must not retry")
        }
        assertEquals(PushEnd.AUTH_FAILED, withTimeout(10.seconds) { push.run() })
        assertFailsWith<AuthenticationFailedException> { mailbox(account("faux")).folders() }
    }

    @Test
    fun `nothing of the account nor of the mail reaches the logs, even with protocol logging on`() = runBlocking<Unit> {
        val logged = StringBuilder()
        Log.sink = Log.Sink { level, format, error -> logged.appendLine("$level $format $error") }
        K9MailLib.setDebug(true)
        // An unknown encoding in an encoded word: the decoder warns, quoting the word.
        deliver("=?UTF-8?X?secret-objet?=")

        mailbox().sync(inbox)
        mailbox().send(message("Réponse"))
        runCatching { mailbox(account("mauvais mot de passe")).folders() }
        runCatching { mailbox(account("mauvais mot de passe")).send(message("Réponse")) }

        val text = logged.toString()
        assertTrue("WARN" in text && "ERROR" in text, "WARN and ERROR do reach the sink:\n$text")
        assertFalse("VERBOSE" in text || "DEBUG" in text || "INFO" in text, "nothing below WARN")
        val saslPlain = Base64.getEncoder().encodeToString("\u0000$address\u0000$password".toByteArray())
        for (secret in listOf(password, "mauvais mot de passe", saslPlain, address, "secret-objet")) {
            assertFalse(secret in text, secret)
        }
    }

    @Test
    fun `a message goes out over SMTP`() {
        mailbox().send(message("Réponse"))

        assertTrue(greenMail.waitForIncomingEmail(5_000, 1))
        assertEquals("Réponse", greenMail.receivedMessages.single().subject)
    }

    @Test
    fun `an opened message brings its headers and its text, an HTML one as text`() = runBlocking<Unit> {
        deliver("html", "Cc: Bob <bob@example.org>\r\nReferences: <a@example.org>\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>Caf&eacute;</p><img src=\"https://x/y.gif\">")
        val mailbox = mailbox()
        mailbox.sync(inbox)
        val opened = mailbox.open("INBOX", store.items.values.single().uid)
        mailbox.close()

        assertEquals(MailAddress("Renee", "renee@example.org"), opened.from)
        assertEquals(listOf(MailAddress(null, address)), opened.to)
        assertEquals(listOf(MailAddress("Bob", "bob@example.org")), opened.cc)
        assertEquals("<html@example.org>", opened.messageId)
        assertEquals(listOf("<a@example.org>"), opened.references)
        assertEquals("Café", opened.text)
        assertFalse(serverInbox.messages.single().isSet(Flags.Flag.SEEN), "opening is not reading on the server")
    }

    @Test
    fun `mark read and unread reach the server`() = runBlocking<Unit> {
        deliver("premier")
        val mailbox = mailbox()
        mailbox.sync(inbox)
        val uid = store.items.values.single().uid

        mailbox.setRead("INBOX", listOf(uid), read = true)
        assertTrue(serverInbox.messages.single().isSet(Flags.Flag.SEEN))
        mailbox.setRead("INBOX", listOf(uid), read = false)
        assertFalse(serverInbox.messages.single().isSet(Flags.Flag.SEEN))
        mailbox.close()
    }

    @Test
    fun `delete moves into the trash, and does nothing without one`() = runBlocking<Unit> {
        deliver("premier")
        val mailbox = mailbox()
        mailbox.sync(inbox)
        val uid = store.items.values.single().uid

        assertFalse(mailbox.delete("INBOX", listOf(uid), trash = null))
        assertEquals(1, serverInbox.messageCount)

        val user = greenMail.managers.userManager.getUser(address)
        val trash = greenMail.managers.imapHostManager.createMailbox(user, "Trash")
        assertTrue(mailbox.delete("INBOX", listOf(uid), trash = "Trash"))
        assertEquals(0, serverInbox.messageCount)
        assertEquals("premier", trash.messages.single().mimeMessage.subject, "kept in the trash, never erased")

        mailbox.sync(inbox)
        assertTrue(store.items.isEmpty(), "gone from the inbox, gone from the Hub")
        mailbox.close()
    }

    @Test
    fun `a reply goes out threaded, in UTF-8`() {
        val reply = OutgoingMail(
            to = listOf(MailAddress("Renée", "renee@example.org")),
            cc = listOf(MailAddress(null, "bob@example.org")),
            subject = "Re: Réunion",
            text = "D'accord pour lundi.\n\nAlice",
            inReplyTo = "<b@example.org>",
            references = listOf("<a@example.org>", "<b@example.org>"),
        )
        mailbox().send(reply.toMessage(MailAddress("Alice", address), Date()))

        assertTrue(greenMail.waitForIncomingEmail(5_000, 2))
        val sent = greenMail.receivedMessages.first()
        assertEquals("Re: Réunion", sent.subject)
        assertEquals("<b@example.org>", sent.getHeader("In-Reply-To").single())
        assertEquals("<a@example.org> <b@example.org>", sent.getHeader("References").single())
        assertEquals("Renée <renee@example.org>", sent.getHeader("To", ", ").let { jakarta.mail.internet.MimeUtility.decodeText(it) })
        assertTrue(sent.getHeader("Message-ID").single().endsWith("@gmail.com>"))
        assertEquals("D'accord pour lundi.\r\n\r\nAlice", (sent.content as String).trimEnd())
    }

    @Test
    fun `the setup's check logs in to both servers, and says when the password is refused`() {
        mailbox().check()
        assertFailsWith<AuthenticationFailedException> { mailbox(account("mauvais mot de passe")).check() }
    }

    @Test
    fun `a login the server refuses with a response code is a refused password, not the network`() {
        // As Gmail answers a Google password, or any login after a few refused ones.
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val imap = Thread {
                server.accept().use { s ->
                    val out = s.getOutputStream().bufferedWriter()
                    val input = s.getInputStream().bufferedReader()
                    out.write("* OK [CAPABILITY IMAP4rev1] ready\r\n"); out.flush()
                    while (true) {
                        val (tag, command) = (input.readLine() ?: break).split(' ', limit = 3).let { it[0] to it.getOrElse(1) { "" } }
                        out.write(
                            when (command.uppercase()) {
                                "CAPABILITY" -> "* CAPABILITY IMAP4rev1\r\n$tag OK done\r\n"
                                "LOGIN" -> "$tag NO [ALERT] Application-specific password required\r\n"
                                else -> "$tag BAD unexpected\r\n"
                            },
                        )
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }
            val refused = MailAccount(
                address, password,
                MailProvider("test", Endpoint("127.0.0.1", server.localPort, tls = false), Endpoint("127.0.0.1", greenMail.smtp.port, tls = false)),
            )
            val e = assertFailsWith<AuthenticationFailedException> { mailbox(refused).check() }
            assertTrue("Application-specific password required" in e.messageFromServer.orEmpty(), e.messageFromServer)
            imap.join(5_000)
        }
    }

    /** A token source that hands [token] out, counting what the servers refused. */
    private class FixedTokens(private val token: String) : com.fsck.k9.mail.oauth.OAuth2TokenProvider {
        var invalidated = 0
        override val usernames: Set<String> = emptySet()
        override fun getToken(timeoutMillis: Long) = token
        override fun invalidateToken() {
            invalidated++
        }
    }

    @Test
    fun `an account signed in through the browser logs in to both servers by XOAUTH2`() = runBlocking<Unit> {
        // GreenMail takes the user's password as the access token.
        val oauth = account().copy(password = "1//refresh", oauth = "google")
        val mailbox = Mailbox(oauth, store, tempDir, oauthTokens = FixedTokens(password))
        mailbox.check()
        deliver("par jeton")
        mailbox.sync(inbox)
        assertEquals(listOf("par jeton"), store.items.values.map { it.subject })
        mailbox.send(message("réponse par jeton"))
        mailbox.close()
    }

    @Test
    fun `a token the servers refuse is invalidated and the login refused`() {
        val tokens = FixedTokens("ya29.revoked")
        val oauth = account().copy(password = "1//refresh", oauth = "google")
        assertFailsWith<AuthenticationFailedException> { Mailbox(oauth, store, tempDir, oauthTokens = tokens).check() }
        assertTrue(tokens.invalidated > 0)
    }

    @Test
    fun `an account signed in through the browser needs its token source`() {
        assertFailsWith<IllegalArgumentException> { Mailbox(account().copy(oauth = "google"), store, tempDir) }
    }

    @Test
    fun `a certificate the system does not trust is refused`() {
        val imaps = GreenMail(ServerSetupTest.IMAPS.dynamicPort()).apply { start() }
        try {
            imaps.setUser(address, address, password)
            val tls = MailProvider("test", Endpoint("localhost", imaps.imaps.port), Endpoint("localhost", 1))
            val error = assertFailsWith<MessagingException> { mailbox(account().copy(provider = tls)).folders() }
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is CertificateValidationException })
        } finally {
            imaps.stop()
        }
    }

    @Test
    fun `a trusted chain for another name is refused`() {
        val imaps = GreenMail(ServerSetupTest.IMAPS.dynamicPort()).apply { start() }
        try {
            imaps.setUser(address, address, password)
            // Trusts any chain: only the name check is left, and GreenMail's certificate does not name localhost.
            val anyChain = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            }
            val local = MailProvider("test", Endpoint("localhost", imaps.imaps.port), Endpoint("localhost", 1))
            val error = assertFailsWith<MessagingException> {
                Mailbox(account().copy(provider = local), store, tempDir, TlsSocketFactory(anyChain)).folders()
            }
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is CertificateValidationException })
        } finally {
            imaps.stop()
        }
    }

    @Test
    fun `the setup's check says which server refused the login, in its own words`() {
        val error = mailbox(account("mauvais mot de passe")).checkError()!!
        assertEquals(Failure.AUTH, error.failure)
        assertEquals(greenMail.imap.port, error.server?.port)
        assertTrue(!error.detail.isNullOrBlank(), "GreenMail's NO text")
        assertEquals(null, mailbox().checkError())
    }

    @Test
    fun `a closed port, implicit TLS on a plain port, a server without STARTTLS each say so`() {
        val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        fun with(imap: Endpoint) = mailbox(account().copy(provider = MailProvider("test", imap, Endpoint("127.0.0.1", greenMail.smtp.port, tls = false))))
        assertEquals(Failure.PORT_CLOSED, with(Endpoint("127.0.0.1", closed, tls = false)).checkError()?.failure)
        assertEquals(Failure.TLS, with(Endpoint("127.0.0.1", greenMail.imap.port)).checkError()?.failure)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Thread {
                server.accept().use { s ->
                    val out = s.getOutputStream().bufferedWriter()
                    val input = s.getInputStream().bufferedReader()
                    out.write("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] ready\r\n"); out.flush()
                    while (true) {
                        val tag = (input.readLine() ?: break).substringBefore(' ')
                        out.write("* CAPABILITY IMAP4rev1 AUTH=PLAIN\r\n$tag OK done\r\n"); out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }
            val error = with(Endpoint.startTls("127.0.0.1", server.localPort)).checkError()!!
            assertEquals(Failure.NO_STARTTLS, error.failure)
            assertEquals(server.localPort, error.server?.port)
        }
    }

    @Test
    fun `a certificate accepted for one server is taken there only, and only that one`() {
        val tls = GreenMail(arrayOf(ServerSetupTest.IMAPS.dynamicPort(), ServerSetupTest.SMTPS.dynamicPort())).apply { start() }
        try {
            tls.setUser(address, address, password)
            val imap = Endpoint("localhost", tls.imaps.port)
            val smtp = Endpoint("localhost", tls.smtps.port)
            var account = account().copy(provider = MailProvider(MailProviders.CUSTOM, imap, smtp))
            val first = Mailbox(account, store, tempDir).checkError()!!
            assertEquals(Failure.CERTIFICATE, first.failure)
            assertEquals(imap, first.server)
            val cert = first.certificate!!
            assertTrue(cert.sha256.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")), cert.sha256)
            assertTrue(cert.names.isNotEmpty())
            // Accepted for another port of the same host: still refused.
            account = account.copy(pins = listOf(Pin("localhost", smtp.port + 1, cert.sha256)))
            assertEquals(imap, Mailbox(account, store, tempDir).checkError()?.server)
            // A wrong fingerprint for the right server: refused.
            account = account.copy(pins = listOf(Pin("localhost", imap.port, "00" + cert.sha256.drop(2))))
            assertEquals(Failure.CERTIFICATE, Mailbox(account, store, tempDir).checkError()?.failure)
            // Accepted for IMAP: SMTP's own certificate is still asked about.
            account = account.copy(pins = listOf(Pin("localhost", imap.port, cert.sha256)))
            val second = Mailbox(account, store, tempDir).checkError()!!
            assertEquals(Failure.CERTIFICATE, second.failure)
            assertEquals(smtp, second.server)
            account = account.copy(pins = account.pins + Pin("localhost", smtp.port, second.certificate!!.sha256))
            assertEquals(null, Mailbox(account, store, tempDir).checkError())
        } finally {
            tls.stop()
        }
    }

    private suspend fun awaitItems(count: Int) = withTimeout(10.seconds) {
        while (store.items.size < count) delay(50)
    }

    private class FakeMailStore(private val failFirst: Boolean = false) : MailStore {
        val items = ConcurrentHashMap<Long, MailItem>()
        private val applies = AtomicInteger()
        private var uidValidity: Long? = null

        override fun folderState(folder: String) = uidValidity?.let { FolderState(it, items.mapValues { (_, item) -> item.flags }) }

        override fun apply(folder: SyncedFolder, uidValidity: Long, plan: SyncPlan, added: List<MailItem>) {
            if (applies.incrementAndGet() == 1 && failFirst) throw IllegalStateException("database is locked")
            this.uidValidity = uidValidity
            plan.remove.forEach(items::remove)
            plan.flagChanges.forEach { (uid, flags) -> items.computeIfPresent(uid) { _, item -> item.copy(flags = flags) } }
            added.forEach { items[it.uid] = it }
        }
    }

    /** [failIdle]: IDLE breaks as soon as it starts, as on a connection the network keeps cutting. */
    private class TimerRefresh(private val failIdle: Boolean = false) : IdleRefreshManager {
        private val timer = Timer(true)
        val timeouts = CopyOnWriteArrayList<Long>()

        override fun startTimer(timeout: Long, callback: () -> Unit): IdleRefreshTimer {
            timeouts += timeout
            if (failIdle) throw IOException("connection reset")
            val task = timer.schedule(timeout) { callback() }
            return object : IdleRefreshTimer {
                override val isWaiting get() = true
                override fun cancel() {
                    task.cancel()
                }
            }
        }

        override fun resetTimers() = Unit
    }

    private object NoWakeLock : WakeLock {
        override fun acquire(timeout: Long) = Unit
        override fun acquire() = Unit
        override fun setReferenceCounted(counted: Boolean) = Unit
        override fun release() = Unit
    }
}
