package dev.bold7.hub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.CertificateValidationException
import com.fsck.k9.mail.power.WakeLock
import com.fsck.k9.mail.store.imap.IdleRefreshManager
import com.fsck.k9.mail.store.imap.IdleRefreshTimer
import dev.bold7.hub.data.Account
import dev.bold7.hub.data.AccountKind
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.HubSink
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.MailContract
import dev.bold7.hub.data.MailResult
import dev.bold7.mail.FolderRole
import dev.bold7.mail.MailAccount
import dev.bold7.mail.MailAddress
import dev.bold7.mail.MailItem
import dev.bold7.mail.MailKeystore
import dev.bold7.mail.MailProviders
import dev.bold7.mail.MailPush
import dev.bold7.mail.MailStore
import dev.bold7.mail.Mailbox
import dev.bold7.mail.HttpTokenPost
import dev.bold7.mail.Loopback
import dev.bold7.mail.OAuthClient
import dev.bold7.mail.OAuthException
import dev.bold7.mail.OAuthServer
import dev.bold7.mail.OAuthServers
import dev.bold7.mail.OAuthTokenSource
import dev.bold7.mail.Redirect
import dev.bold7.mail.authorizationUrl
import dev.bold7.mail.exchangeCode
import dev.bold7.mail.newSecret
import dev.bold7.mail.pkceChallenge
import dev.bold7.mail.OutgoingMail
import dev.bold7.mail.PushEnd
import dev.bold7.mail.PushPolicy
import dev.bold7.mail.SyncPlan
import dev.bold7.mail.SyncedFolder
import dev.bold7.mail.toMessage
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.thunderbird.legacy.logging.Log as MailLog

private const val MAIL_TAG = "Bold7/mail"

/**
 * The mail accounts of this process: each account sealed
 * by the Keystore ([MailKeystore]), synced into the Hub's store ([HubMailStore]) and pushed by IMAP
 * IDLE on its inbox while a network is there, without a foreground service: the notification
 * listener already keeps this process. Messages reaches it through the provider's calls
 * ([MailContract]): blocking, on a binder thread; the push runs on [io]. Nothing read from a mail,
 * no address and no password is logged: counts and class names only.
 */
class MailHost(private val app: Context, private val sink: HubSink) {
    private val io = Executors.newCachedThreadPool { Thread(it, "hub-mail") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val refresh by lazy { AlarmRefresh(app) }
    private val wakeLock by lazy { PartialWakeLock(app) }
    private val temp get() = File(app.cacheDir, "mail").apply { mkdirs() }

    /**
     * An account and its push. [account] changes when its signature does, or when Microsoft
     * rotates its refresh token ([tokens], for an account signed in through the browser).
     */
    private class Running(@Volatile var account: MailAccount, var tokens: OAuthTokenSource? = null, var job: Job? = null, var push: MailPush? = null)

    private val accounts = ConcurrentHashMap<String, Running>()
    @Volatile private var loaded = false
    @Volatile private var online = false
    @Volatile private var network: Network? = null
    val syncs = AtomicInteger()
    val failures = AtomicInteger()
    @Volatile private var lastFailure: String? = null

    init {
        // The protocol code's log, cut at the source: formats and class names.
        MailLog.sink = MailLog.Sink { level, format, error ->
            Log.println(if (level == MailLog.Level.ERROR) Log.ERROR else Log.WARN, MAIL_TAG, format + error?.let { " ($it)" }.orEmpty())
        }
    }

    /** Loads the accounts, then follows the network: push runs while there is one, stops when it goes. */
    fun start() {
        scope.launch { load() }
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            // Another default network (Wi-Fi to mobile): the connections of the old one are dead.
            override fun onAvailable(n: Network) {
                if (network != null && network != n) accounts.values.forEach(::halt)
                network = n
                online = true
                scope.launch {
                    load()
                    accounts.keys.forEach(::run)
                }
            }

            override fun onLost(n: Network) {
                if (n != network) return
                network = null
                online = false
                accounts.values.forEach(::halt)
            }
        })
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        val ids = MailKeystore.ids(app)
        for (id in ids) {
            val account = MailKeystore.vault(app, id).load()
            if (account == null) {
                // Unreadable (key lost with a reinstall): its items go with it, the user enters it again.
                runBlocking { sink.removeAccount(id) }
                continue
            }
            accounts[id] = Running(account, tokensFor(id, account))
            runBlocking { sink.putAccount(hubAccount(id, account.address)) }
        }
        loaded = true
    }

    /**
     * Bold7's OAuth clients, from local.properties through BuildConfig; none when
     * the build had none: browser sign-in is then off, and Email Setup says so.
     */
    private val clients: Map<String, OAuthClient> = buildMap {
        if (BuildConfig.OAUTH_GOOGLE_ID.isNotEmpty()) put(OAuthServers.GOOGLE.id, OAuthClient(BuildConfig.OAUTH_GOOGLE_ID, BuildConfig.OAUTH_GOOGLE_SECRET.ifEmpty { null }))
        if (BuildConfig.OAUTH_MICROSOFT_ID.isNotEmpty()) put(OAuthServers.MICROSOFT.id, OAuthClient(BuildConfig.OAUTH_MICROSOFT_ID))
    }

    /** Account [id]'s access tokens when it signs in through the browser; a new refresh token is sealed at once. */
    private fun tokensFor(id: String, account: MailAccount): OAuthTokenSource? {
        val server = account.oauth?.let(OAuthServers::byId) ?: return null
        val client = clients[server.id] ?: return null
        return OAuthTokenSource(server, client, account.password, HttpTokenPost, System::currentTimeMillis) { refresh ->
            val r = accounts[id] ?: return@OAuthTokenSource
            synchronized(r) {
                r.account = r.account.copy(password = refresh)
                MailKeystore.vault(app, id).save(r.account)
            }
        }
    }

    /** A connection to [r]'s servers; an account signed in through the browser needs this build's client. */
    private fun mailbox(r: Running, store: MailStore = NoStore): Mailbox {
        if (r.account.oauth != null && r.tokens == null) throw AuthenticationFailedException("No OAuth client in this build")
        return Mailbox(r.account, store, temp, oauthTokens = r.tokens)
    }

    private fun hubAccount(id: String, address: String) = Account(id, AccountKind.EMAIL, address, address = address, position = 2)

    /** Account [id]'s push, unless it runs: every folder synced once, then IDLE on the inbox. */
    @Synchronized
    private fun run(id: String) {
        val r = accounts[id] ?: return
        if (!online || r.job?.isActive == true) return
        r.job = scope.launch {
            var tries = 0
            while (true) {
                val mailbox = try {
                    mailbox(r, HubMailStore(sink, id))
                } catch (e: AuthenticationFailedException) {
                    return@launch failed("push: no OAuth client in this build, push stopped")
                }
                try {
                    val folders = mailbox.folders()
                    // Sent and drafts once (they change from here, or from another client: at each start).
                    for (f in folders.sortedByDescending { it.role != FolderRole.INBOX }) sync(mailbox, f)
                    val inbox = folders.firstOrNull { it.role == FolderRole.INBOX } ?: return@launch
                    val push = MailPush(mailbox, inbox, refresh, wakeLock, io)
                    r.push = push
                    when (push.run()) {
                        PushEnd.AUTH_FAILED -> failed("push: password refused, push stopped until the account is entered again")
                        // No IDLE on the server: a 30 min poll. A
                        // delay, no alarm: it runs when the phone is awake, never wakes it.
                        PushEnd.NOT_SUPPORTED -> while (true) {
                            delay(30.minutes)
                            sync(mailbox, inbox)
                        }
                        PushEnd.STOPPED -> Unit
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthenticationFailedException) {
                    return@launch failed("push: password refused, push stopped until the account is entered again")
                } catch (e: Exception) {
                    // A server or a network that failed before IDLE: again after MailPush's back-off.
                    failed("push: ${e.javaClass.simpleName}")
                } finally {
                    mailbox.close()
                }
                delay(PushPolicy.retryDelay(++tries))
            }
        }
    }

    private suspend fun sync(mailbox: Mailbox, folder: SyncedFolder) {
        mailbox.sync(folder)
        syncs.incrementAndGet()
    }

    private fun halt(r: Running) {
        r.push?.stop()
        r.push = null
        r.job?.cancel()
        r.job = null
    }

    private fun failed(what: String) {
        failures.incrementAndGet()
        lastFailure = what
        Log.w(MAIL_TAG, what)
    }

    /** One of [MailContract]'s calls, from Messages, the phone unlocked (Provider checked both). */
    fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        load()
        val out = Bundle()
        val result = when (method) {
            MailContract.METHOD_ACCOUNTS -> {
                val list = accounts.entries.sortedBy { it.key }
                out.putStringArray(MailContract.EXTRA_IDS, list.map { it.key }.toTypedArray())
                out.putStringArray(MailContract.EXTRA_ADDRESSES, list.map { it.value.account.address }.toTypedArray())
                out.putStringArray(MailContract.EXTRA_SIGNATURES, list.map { it.value.account.signature.orEmpty() }.toTypedArray())
                out.putStringArray(MailContract.EXTRA_BROWSER, BROWSER_NAMES.filterKeys(clients::containsKey).values.toTypedArray())
                MailResult.OK
            }
            MailContract.METHOD_ADD -> add(extras ?: Bundle(), out)
            MailContract.METHOD_REMOVE -> remove(arg)
            MailContract.METHOD_OPEN -> open(extras, out)
            MailContract.METHOD_SEND -> send(extras ?: Bundle())
            MailContract.METHOD_SIGNATURE -> signature(arg, extras?.getString(MailContract.EXTRA_SIGNATURE))
            MailContract.METHOD_BROWSER_BEGIN -> browserBegin(extras?.getString(MailContract.EXTRA_ADDRESS)?.trim().orEmpty(), out)
            MailContract.METHOD_BROWSER_FINISH -> browserFinish(out)
            else -> throw IllegalArgumentException("Unknown mail call")
        }
        out.putString(MailContract.EXTRA_RESULT, result.name)
        return out
    }

    /** Both servers logged in to before anything is kept; the address, once only. */
    private fun add(x: Bundle, out: Bundle): MailResult {
        val address = x.getString(MailContract.EXTRA_ADDRESS)?.trim().orEmpty()
        val password = x.getString(MailContract.EXTRA_PASSWORD).orEmpty()
        if (!address.matches(EMAIL) || password.isEmpty()) return MailResult.REFUSED
        if (accounts.values.any { it.account.address.equals(address, ignoreCase = true) }) return MailResult.REFUSED
        val imapHost = x.getString(MailContract.EXTRA_IMAP_HOST)?.trim().orEmpty()
        val smtpHost = x.getString(MailContract.EXTRA_SMTP_HOST)?.trim().orEmpty()
        val provider = MailProviders.forAddress(address)
            ?: if (imapHost.isNotEmpty() && smtpHost.isNotEmpty()) {
                MailProviders.custom(imapHost, x.getInt(MailContract.EXTRA_IMAP_PORT, 993), smtpHost, x.getInt(MailContract.EXTRA_SMTP_PORT, 465))
            } else {
                return MailResult.SERVERS
            }
        val account = MailAccount(address, password, provider)
        val checked = attempt { Mailbox(account, NoStore, temp).run { try { check() } finally { close() } } }
        if (checked != MailResult.OK) return checked
        out.putString(MailContract.EXTRA_ID, keep(account))
        return MailResult.OK
    }

    /** [account], checked, sealed and pushed: its new id. */
    private fun keep(account: MailAccount): String {
        val id = "mail-" + ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        MailKeystore.vault(app, id).save(account)
        accounts[id] = Running(account, tokensFor(id, account))
        runBlocking { sink.putAccount(hubAccount(id, account.address)) }
        Log.i(MAIL_TAG, "account added (${account.provider.id}${account.oauth?.let { ", browser" }.orEmpty()})")
        scope.launch { run(id) }
        return id
    }

    /** Use Auto Signature (guide 7.1 p. 90, B): [text], or off when null or blank. */
    private fun signature(id: String?, text: String?): MailResult {
        val r = id?.let(accounts::get) ?: return MailResult.REFUSED
        synchronized(r) {
            r.account = r.account.copy(signature = text?.takeIf { it.isNotBlank() })
            MailKeystore.vault(app, id).save(r.account)
        }
        return MailResult.OK
    }

    /**
     * A sign-in through the browser (RFC 8252): a loopback listener, PKCE and a
     * state, all in this process; the page opened by Messages in the system's browser, never a
     * WebView. One at a time: a new one ends the last.
     */
    private class BrowserFlow(val address: String, val server: OAuthServer, val client: OAuthClient, val loop: Loopback, val verifier: String, val state: String) {
        @Volatile var heard = false
        val done = CompletableFuture<Pair<MailResult, String?>>()
    }

    private val flowLock = Any()
    private var flow: BrowserFlow? = null

    private fun browserBegin(address: String, out: Bundle): MailResult {
        if (!address.matches(EMAIL)) return MailResult.REFUSED
        if (accounts.values.any { it.account.address.equals(address, ignoreCase = true) }) return MailResult.REFUSED
        val provider = MailProviders.forAddress(address) ?: return MailResult.UNAVAILABLE
        val server = OAuthServers.forProvider(provider.id) ?: return MailResult.UNAVAILABLE
        val client = clients[server.id] ?: return MailResult.UNAVAILABLE
        val random = SecureRandom()
        val f = BrowserFlow(address, server, client, Loopback(server.loopbackHost), newSecret(random), newSecret(random))
        synchronized(flowLock) {
            flow?.loop?.close()
            flow = f
        }
        scope.launch {
            val redirect = f.loop.await(f.state, BROWSER_WAIT_MS)
            f.heard = redirect != null
            f.loop.close()
            f.done.complete(browserSignedIn(f, redirect))
        }
        out.putString(MailContract.EXTRA_URL, authorizationUrl(server, client, f.loop.redirect, pkceChallenge(f.verifier), f.state, address))
        return MailResult.OK
    }

    /** The code exchanged, then both servers logged in to by XOAUTH2 before anything is kept, as [add] does. */
    private fun browserSignedIn(f: BrowserFlow, redirect: Redirect?): Pair<MailResult, String?> {
        val code = (redirect as? Redirect.Code)?.code ?: return MailResult.CANCELLED to null
        val tokens = try {
            exchangeCode(f.server, f.client, code, f.verifier, f.loop.redirect, HttpTokenPost, System::currentTimeMillis)
        } catch (e: OAuthException) {
            failed("browser sign-in: ${e.error}")
            return MailResult.AUTH to null
        } catch (e: IOException) {
            failed("browser sign-in: ${e.javaClass.simpleName}")
            return MailResult.NETWORK to null
        }
        // Without a refresh token, the account would stop within the hour.
        val refresh = tokens.refresh ?: return (MailResult.AUTH to null).also { failed("browser sign-in: no refresh token") }
        val provider = MailProviders.forAddress(f.address) ?: return MailResult.UNAVAILABLE to null
        val account = MailAccount(f.address, refresh, provider, oauth = f.server.id)
        // The sign-in's own access token for the check: no refresh, so no rotated token to lose.
        val source = OAuthTokenSource(f.server, f.client, refresh, HttpTokenPost, System::currentTimeMillis, initial = tokens) {}
        val checked = attempt { Mailbox(account, NoStore, temp, oauthTokens = source).run { try { check() } finally { close() } } }
        if (checked != MailResult.OK) return checked to null
        if (accounts.values.any { it.account.address.equals(f.address, ignoreCase = true) }) return MailResult.REFUSED to null
        return MailResult.OK to keep(account)
    }

    /**
     * Messages is back from the browser: the sign-in's result, waited for. A redirect not heard
     * within a few seconds will not come (the tab was closed first): the listener ends.
     */
    private fun browserFinish(out: Bundle): MailResult {
        val f = synchronized(flowLock) { flow } ?: return MailResult.REFUSED
        val (result, id) = try {
            try {
                f.done.get(3, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                if (!f.heard) f.loop.close()
                f.done.get(2, TimeUnit.MINUTES)
            }
        } catch (e: Exception) {
            f.loop.close()
            MailResult.CANCELLED to null
        }
        synchronized(flowLock) { if (flow === f) flow = null }
        id?.let { out.putString(MailContract.EXTRA_ID, it) }
        return result
    }

    private fun remove(id: String?): MailResult {
        val r = id?.let(accounts::remove) ?: return MailResult.REFUSED
        halt(r)
        MailKeystore.vault(app, id).clear()
        runBlocking { sink.removeAccount(id) }
        Log.i(MAIL_TAG, "account removed")
        return MailResult.OK
    }

    private fun open(x: Bundle?, out: Bundle): MailResult {
        val key = runCatching { x?.let { hubKeys(it).single() } }.getOrNull() ?: return MailResult.REFUSED
        val r = accounts[key.accountId] ?: return MailResult.REFUSED
        val at = MailKey.parse(key.sourceKey) ?: return MailResult.REFUSED
        var opened: dev.bold7.mail.OpenedMail? = null
        val result = attempt {
            val mailbox = mailbox(r)
            try {
                opened = runBlocking { mailbox.open(at.folder, at.uid) }
            } finally {
                mailbox.close()
            }
        }
        val m = opened ?: return result
        fun packed(list: List<MailAddress>) = list.map { MailContract.address(it.email, it.name) }.toTypedArray()
        out.putString(MailContract.EXTRA_FROM, m.from?.let { MailContract.address(it.email, it.name) })
        out.putStringArray(MailContract.EXTRA_TO, packed(m.to))
        out.putStringArray(MailContract.EXTRA_CC, packed(m.cc))
        out.putStringArray(MailContract.EXTRA_REPLY_TO, packed(m.replyTo))
        out.putString(MailContract.EXTRA_SUBJECT, m.subject)
        out.putLong(MailContract.EXTRA_DATE, m.sentAt)
        out.putString(MailContract.EXTRA_MESSAGE_ID, m.messageId)
        out.putStringArray(MailContract.EXTRA_REFERENCES, m.references.toTypedArray())
        out.putString(MailContract.EXTRA_TEXT, m.text.take(MailContract.TEXT_MAX))
        out.putString(MailContract.EXTRA_OWN, r.account.address)
        return MailResult.OK
    }

    private fun send(x: Bundle): MailResult {
        val id = x.getString(MailContract.EXTRA_ACCOUNT) ?: return MailResult.REFUSED
        val r = accounts[id] ?: return MailResult.REFUSED
        fun list(extra: String) = x.getStringArray(extra).orEmpty().map { MailContract.parse(it).let { (email, name) -> MailAddress(name, email) } }
        val mail = OutgoingMail(
            to = list(MailContract.EXTRA_TO),
            cc = list(MailContract.EXTRA_CC),
            subject = x.getString(MailContract.EXTRA_SUBJECT).orEmpty(),
            text = x.getString(MailContract.EXTRA_TEXT).orEmpty(),
            inReplyTo = x.getString(MailContract.EXTRA_IN_REPLY_TO),
            references = x.getStringArray(MailContract.EXTRA_REFERENCES).orEmpty().toList(),
        )
        if (mail.to.isEmpty() && mail.cc.isEmpty()) return MailResult.REFUSED
        val result = attempt { mailbox(r).run { try { send(mail.toMessage(MailAddress(null, r.account.address), Date())) } finally { close() } } }
        if (result == MailResult.OK) {
            Log.i(MAIL_TAG, "mail sent")
            // The server files its copy in Sent (Gmail does): the Hub shows it at the next sync of Sent.
            scope.launch { syncRole(id, FolderRole.SENT) }
        }
        return result
    }

    private suspend fun syncRole(id: String, role: FolderRole) {
        val r = accounts[id] ?: return
        val mailbox = try {
            mailbox(r, HubMailStore(sink, id))
        } catch (e: AuthenticationFailedException) {
            return failed("sync: no OAuth client in this build")
        }
        try {
            mailbox.folders().firstOrNull { it.role == role }?.let { sync(mailbox, it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed("sync: ${e.javaClass.simpleName}")
        } finally {
            mailbox.close()
        }
    }

    /**
     * Mark Read, Mark Unread and Delete of mail items in Messages, carried to their servers off
     * the binder thread: \Seen set or cleared, a deleted mail moved to the trash (never erased).
     * A failure (no network) leaves the server as it was: the Hub keeps the user's state, and a
     * later sync does not undo it (HubMailStore.merged).
     */
    fun written(method: String, keys: List<ItemKey>) {
        val mail = keys.filter { accounts.containsKey(it.accountId) }.groupBy { it.accountId }
        if (mail.isEmpty()) return
        scope.launch {
            for ((id, ks) in mail) {
                val r = accounts[id] ?: continue
                val mailbox = try {
                    mailbox(r)
                } catch (e: AuthenticationFailedException) {
                    failed("$method on the server: no OAuth client in this build")
                    continue
                }
                try {
                    val byFolder = ks.mapNotNull { MailKey.parse(it.sourceKey) }.groupBy({ it.folder }, { it.uid })
                    val trash = if (method == HubContract.METHOD_DELETE) mailbox.trash() else null
                    for ((folder, uids) in byFolder) when (method) {
                        HubContract.METHOD_MARK_READ -> mailbox.setRead(folder, uids, read = true)
                        HubContract.METHOD_MARK_UNREAD -> mailbox.setRead(folder, uids, read = false)
                        HubContract.METHOD_DELETE -> mailbox.delete(folder, uids, trash)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed("$method on the server: ${e.javaClass.simpleName}")
                } finally {
                    mailbox.close()
                }
            }
        }
    }

    fun dump(out: java.io.PrintWriter) {
        out.println("Mail: ${accounts.size} accounts, ${if (online) "online" else "offline"}, ${syncs.get()} syncs, ${failures.get()} failures${lastFailure?.let { " (last: $it)" }.orEmpty()}")
        accounts.values.forEach { r ->
            val p = r.push
            out.println("  account${if (r.account.oauth != null) " (browser)" else ""}: push ${if (r.job?.isActive == true) "running" else "stopped"}" +
                (p?.let { ", ${it.syncs.get()} push syncs, ${it.syncErrors.get()} sync errors, ${it.failures.get()} IDLE failures in a row" } ?: ""))
        }
    }

    private object NoStore : MailStore {
        override fun folderState(folder: String) = null
        override fun apply(folder: SyncedFolder, uidValidity: Long, plan: SyncPlan, added: List<MailItem>) = Unit
    }

    private companion object {
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        /** The names Email Setup shows for the browser's sign-in, by OAuth server (D). */
        val BROWSER_NAMES = mapOf(OAuthServers.GOOGLE.id to "Gmail", OAuthServers.MICROSOFT.id to "Outlook")

        /** How long the redirect is listened for: a sign-in with a second factor takes minutes (D). */
        const val BROWSER_WAIT_MS = 10 * 60_000L

        fun hubKeys(x: Bundle) = dev.bold7.hub.data.itemKeys(x.getStringArray(HubContract.EXTRA_ACCOUNTS), x.getStringArray(HubContract.EXTRA_KEYS))
    }

    /** [block]'s failure as a result: refused password, untrusted certificate, or the network. */
    private inline fun attempt(block: () -> Unit): MailResult = try {
        block()
        MailResult.OK
    } catch (e: Exception) {
        val chain = generateSequence<Throwable>(e) { it.cause }
        when {
            chain.any { it is AuthenticationFailedException } -> MailResult.AUTH
            chain.any { it is CertificateValidationException } -> MailResult.CERTIFICATE
            else -> {
                failed("call: ${e.javaClass.simpleName}")
                MailResult.NETWORK
            }
        }
    }
}

/**
 * IDLE's renewal every 15 minutes (PushPolicy.IDLE_REFRESH), the only periodic wakeup push costs
 * an exact alarm allowed in Doze (USE_EXACT_ALARM), sent to a receiver
 * registered here, not exported. Its callback runs on its own thread, never the main one.
 */
private class AlarmRefresh(private val context: Context) : IdleRefreshManager {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(HandlerThread("hub-mail-refresh").apply { start() }.looper)
    private val next = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, Pair<PendingIntent, () -> Unit>>()
    private val action = "${context.packageName}.MAIL_IDLE_REFRESH"

    init {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                pending.remove(intent.getIntExtra(EXTRA_CODE, -1))?.second?.invoke()
            }
        }, IntentFilter(action), null, handler, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun startTimer(timeout: Long, callback: () -> Unit): IdleRefreshTimer {
        val code = next.incrementAndGet()
        val intent = PendingIntent.getBroadcast(
            context, code, Intent(action).setPackage(context.packageName).putExtra(EXTRA_CODE, code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        pending[code] = intent to callback
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + timeout, intent)
        return object : IdleRefreshTimer {
            override val isWaiting get() = pending.containsKey(code)
            override fun cancel() {
                pending.remove(code)?.let { alarms.cancel(it.first) }
            }
        }
    }

    /** Every renewal now (the network changed). */
    override fun resetTimers() {
        for (code in pending.keys.toList()) {
            val (intent, callback) = pending.remove(code) ?: continue
            alarms.cancel(intent)
            handler.post(callback)
        }
    }

    private companion object {
        const val EXTRA_CODE = "code"
    }
}

/** Held only while a response is handled: the protocol code releases it during IDLE's read. */
private class PartialWakeLock(context: Context) : WakeLock {
    private val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bold7:mail")

    override fun acquire(timeout: Long) = lock.acquire(timeout)
    override fun acquire() = lock.acquire(10 * 60_000L)
    override fun setReferenceCounted(counted: Boolean) = lock.setReferenceCounted(counted)
    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
