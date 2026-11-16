package dev.bold7.mail

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.power.WakeLock
import com.fsck.k9.mail.store.imap.IdleRefreshManager
import com.fsck.k9.mail.store.imap.IdleRefreshTimer
import com.fsck.k9.mail.store.imap.IdleResult
import com.fsck.k9.mail.store.imap.ImapFolderIdler
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.legacy.logging.Log

enum class PushEnd {
    STOPPED,

    /** The server has no IDLE: the host falls back to a sync every 30 minutes. */
    NOT_SUPPORTED,

    /** Wrong or revoked app password: never retried, the user has to enter it again. */
    AUTH_FAILED,
}

/**
 * Push for one folder, as Thunderbird's ImapFolderPusher: one connection stays in IMAP IDLE, and
 * each change it reports asks for a sync, run on a second connection while IDLE goes on, so a
 * message arriving during a sync is not missed. Requests are conflated: one sync covers a burst.
 *
 * [refresh] renews IDLE every [PushPolicy.IDLE_REFRESH]; on the phone it must fire in Doze (an
 * exact alarm allowed while idle): it is the only periodic wakeup push costs. [run] holds one
 * thread of [io] for as long as push lasts. [retryDelay] is [PushPolicy.retryDelay], shortened
 * by tests only.
 */
class MailPush(
    private val mailbox: Mailbox,
    private val folder: SyncedFolder,
    private val refresh: IdleRefreshManager,
    wakeLock: WakeLock,
    private val io: CoroutineDispatcher,
    private val retryDelay: (failures: Int) -> Duration = PushPolicy::retryDelay,
) {
    @Volatile
    private var stopped = false
    private val stopSignal = CompletableDeferred<Unit>()

    // A stop() landing while the idler opens the folder would be forgotten by it (it resets its
    // flag when IDLE starts): it is repeated as soon as IDLE is sent, when the timer starts.
    private val guardedRefresh = object : IdleRefreshManager {
        override fun startTimer(timeout: Long, callback: () -> Unit): IdleRefreshTimer =
            refresh.startTimer(timeout, callback).also { if (stopped) idler.stop() }

        override fun resetTimers() = refresh.resetTimers()
    }

    private val idler: ImapFolderIdler = mailbox.idler(folder, guardedRefresh, wakeLock)

    /**
     * For the diagnostic: syncs done, syncs failed, and IDLE failures in a row (back to 0 only
     * when IDLE itself ran: a sync that works while IDLE keeps failing does not reset the back-off).
     */
    val syncs = AtomicInteger()
    val syncErrors = AtomicInteger()
    val failures = AtomicInteger()

    suspend fun run(): PushEnd = coroutineScope {
        val requests = Channel<Unit>(Channel.CONFLATED)
        var authFailed = false
        val syncer = launch(io) {
            for (request in requests) {
                try {
                    mailbox.sync(folder)
                    syncs.incrementAndGet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthenticationFailedException) {
                    authFailed = true
                    stop()
                } catch (e: Exception) {
                    // Network, server, a malformed message, or the Hub's store: counted, logged by
                    // class (Log), and the next push event or retry syncs again.
                    syncErrors.incrementAndGet()
                    Log.w(e, "Mail sync failed")
                } finally {
                    // An idle pooled connection could be dead (NAT) by the next sync: reconnect then.
                    mailbox.close()
                }
            }
        }
        // Whatever ends the syncer before its channel closes ends push too: never IDLE without sync.
        syncer.invokeOnCompletion { if (it != null) stop() }
        val end = withContext(io) { idle(requests) }
        requests.close()
        syncer.join()
        if (authFailed) PushEnd.AUTH_FAILED else end
    }

    /** From any thread: ends IDLE or the back-off wait, and [run] returns [PushEnd.STOPPED]. */
    fun stop() {
        stopped = true
        stopSignal.complete(Unit)
        idler.stop()
        mailbox.close()
    }

    private suspend fun idle(requests: SendChannel<Unit>): PushEnd {
        requests.trySend(Unit)
        while (!stopped) {
            try {
                val result = idler.idle()
                failures.set(0)
                when (result) {
                    IdleResult.SYNC -> requests.trySend(Unit)
                    IdleResult.STOPPED -> break
                    IdleResult.NOT_SUPPORTED -> return PushEnd.NOT_SUPPORTED
                }
            } catch (e: AuthenticationFailedException) {
                Log.w("Mail push: authentication refused")
                return PushEnd.AUTH_FAILED
            } catch (e: MessagingException) {
                retryAfter(e, requests)
            } catch (e: IOException) {
                retryAfter(e, requests)
            }
        }
        return PushEnd.STOPPED
    }

    private suspend fun retryAfter(e: Exception, requests: SendChannel<Unit>) {
        if (stopped) return
        Log.w(e, "Mail push: IDLE failed, retrying after a back-off")
        withTimeoutOrNull(retryDelay(failures.incrementAndGet())) { stopSignal.await() }
        // Changes may have come while disconnected.
        requests.trySend(Unit)
    }
}
