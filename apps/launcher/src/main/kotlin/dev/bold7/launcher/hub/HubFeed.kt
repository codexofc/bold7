package dev.bold7.launcher.hub

import dev.bold7.hub.data.Account
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKey
import dev.bold7.launcher.home.UNREAD_OTHER
import dev.bold7.launcher.notices.NoticeHeader
import dev.bold7.launcher.notices.NoticeLine
import dev.bold7.launcher.notices.NoticeNames
import dev.bold7.launcher.notices.notices
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Hub holds, and the apps whose notifications repeat it (withoutEchoes). */
data class HubSnapshot(
    val items: List<HubItem>,
    val smsApp: String?,
    val dialer: String?,
    /** The Hub's accounts in their order: only their id, kind and position count here. */
    val accounts: List<Account> = emptyList(),
    /** The made-up items of the measure (demoSnapshot): never written back, a visit included. */
    val demo: Boolean = false,
)

/** The Hub's provider and Android's settings, reached off the main thread only. */
interface HubReader {
    /** Null: nothing could be read (no Hub app, before the first unlock, refused, failed). */
    fun read(): HubSnapshot?

    fun format(): HubFormat

    /**
     * Asks the Hub's provider to [method] (HubContract.METHOD_MARK_READ and the others) these
     * items, at most HubContract.KEYS_MAX. Throws what the provider throws.
     */
    fun write(method: String, keys: List<ItemKey>)
}

data class HubListState(
    /** False until the first read. */
    val loaded: Boolean = false,
    /** What is new, for the splats of the home's icons and the banner's counts. */
    val badges: HubBadges = HubBadges(),
    /** The list of notifications under the home's banner. */
    val notices: List<NoticeLine> = emptyList(),
)

/**
 * The Hub's items, read and worked out on [io], one read at a time: changes that arrive
 * while one waits make one read (a ContentObserver calls [reload]). A failed read keeps what is
 * shown.
 */
class HubFeed(
    private val reader: HubReader,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    /** The words of the list of notifications; null: no list worked out. */
    private val noticeNames: NoticeNames? = null,
    /** An app's name on the home, for its header in that list; called on [io]. */
    private val appName: (String) -> String? = { null },
    /** Draws an app's indicator (its small icon) before the state that shows it is published; called on [io]. */
    private val drawIndicator: (String) -> Unit = {},
    /** A read or its working out that threw: reported, and the next change reads again. */
    private val onError: (RuntimeException) -> Unit,
) {
    private val mutable = MutableStateFlow(HubListState())
    val state: StateFlow<HubListState> = mutable.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val stale = AtomicBoolean(false)

    /** The last read, for the actions to name their items. */
    @Volatile private var snapshot: HubSnapshot? = null

    init {
        scope.launch(io) {
            for (request in requests) {
                mutable.value = try {
                    if (stale.getAndSet(false) || snapshot == null) reader.read()?.let { snapshot = it }
                    when (val s = snapshot) {
                        null -> mutable.value.copy(loaded = true)
                        else -> {
                            val format = reader.format()
                            val badges = hubBadges(s.accounts, s.items, s.smsApp, s.dialer)
                            val notices = noticeNames?.let { notices(s.accounts, s.items, s.smsApp, s.dialer, format, it, appName) }.orEmpty()
                            badges.appCounts.keys.forEach(drawIndicator)
                            // The list's other apps too (an SMS app's chats count as texts, not as an app).
                            notices.forEach { if (it is NoticeHeader && it.frame == UNREAD_OTHER) it.app?.let(drawIndicator) }
                            HubListState(loaded = true, badges = badges, notices = notices)
                        }
                    }
                } catch (e: RuntimeException) {
                    // Uncaught, it would end this loop: the counts would never change again.
                    onError(e)
                    mutable.value.copy(loaded = true)
                }
            }
        }
    }

    fun reload() {
        stale.set(true)
        requests.trySend(Unit)
    }

    /**
     * Reads [item]'s conversation through the provider (a row of the list of notifications
     * opened), its items named from the last read: nothing before the first. The provider's
     * change notification brings the new state. A failure is reported, the list stays.
     */
    fun markRead(item: HubItem) {
        scope.launch(io) {
            val s = snapshot ?: return@launch
            try {
                readKeys(item, s).chunked(HubContract.KEYS_MAX).forEach { reader.write(HubContract.METHOD_MARK_READ, it) }
            } catch (e: RuntimeException) {
                onError(e)
            }
        }
    }
}
