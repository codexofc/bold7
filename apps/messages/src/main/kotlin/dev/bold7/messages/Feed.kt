package dev.bold7.messages

import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.ItemKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The Hub's provider and Android's settings, reached off the main thread only (apps/hub, HubContract). */
interface InboxReader {
    /** Null: nothing could be read (no Hub app, before the first unlock, refused, failed). */
    fun read(): Snapshot?

    fun format(): InboxFormat

    /** Asks the provider to [method] these items, at most HubContract.KEYS_MAX. Throws what it throws. */
    fun write(method: String, keys: List<ItemKey>)

    /** Tells the provider each account was seen up to the arrival it maps to. Throws what it throws. */
    fun markSeen(upTo: Map<String, Long>)
}

/** The lines worked out for [filter] and [search]: the activity drops those of a view it left. */
data class FeedState(
    val lines: List<InboxLine> = emptyList(),
    val loaded: Boolean = false,
    val filter: Filter = Filter.ALL,
    val search: String? = null,
    val account: String? = null,
    val searchIn: SearchIn = SearchIn.ANY,
)

/**
 * The inbox's lines, read and worked out on [io], one read at a time: changes that arrive while
 * one waits make one read (a ContentObserver calls [reload]). A failed read keeps what is shown.
 * Another view ([show]) works the last read out again, without reading. Taken from the launcher's
 * hub/HubFeed.kt.
 */
class Feed(
    private val reader: InboxReader,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    /** A read or its working out that threw: reported, and the next change reads again. */
    private val onError: (RuntimeException) -> Unit,
    /**
     * What an action asks of Google Messages (apiCalls), run on [io] before the Hub
     * writes; a refusal is the caller's to report, the Hub writes all the same.
     */
    private val bridge: (List<ApiCall>) -> Unit = {},
) {
    private val mutable = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = mutable.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val stale = AtomicBoolean(false)
    @Volatile private var filter = Filter.ALL
    @Volatile private var search: String? = null
    @Volatile private var account: String? = null
    @Volatile private var searchIn = SearchIn.ANY
    @Volatile private var viewing = false

    /** The last read, for the actions to name their items. */
    @Volatile private var snapshot: Snapshot? = null

    init {
        scope.launch(io) {
            for (request in requests) {
                mutable.value = try {
                    if (stale.getAndSet(false) || snapshot == null) reader.read()?.let { snapshot = it }
                    val f = filter
                    val q = search
                    val a = account
                    val by = searchIn
                    when (val s = snapshot) {
                        null -> mutable.value.copy(loaded = true, filter = f, search = q, account = a, searchIn = by)
                        else -> {
                            // Opening Messages sees what is new in the view shown (Text Messages:
                            // the texts); no loop: seen up to the arrivals read, the next read
                            // finds nothing new there.
                            val seen = if (viewing && !s.demo) toSee(s, f, a) else emptyMap()
                            if (seen.isNotEmpty()) {
                                try {
                                    reader.markSeen(seen)
                                } catch (e: RuntimeException) {
                                    onError(e)
                                }
                            }
                            FeedState(inboxLines(s, f, q, reader.format(), a, by), loaded = true, filter = f, search = q, account = a, searchIn = by)
                        }
                    }
                } catch (e: RuntimeException) {
                    // Uncaught, it would end this loop: the list would never change again.
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

    /** Shows [filter] and [search] (in [searchIn]), within [account] when one is given, from the last read. */
    fun show(filter: Filter, search: String?, account: String? = null, searchIn: SearchIn = SearchIn.ANY) {
        if (filter == this.filter && search == this.search && account == this.account && searchIn == this.searchIn) return
        this.filter = filter
        this.search = search
        this.account = account
        this.searchIn = searchIn
        requests.trySend(Unit)
    }

    /** Whether Messages is on screen: what is new is then seen, at each read while it lasts. */
    fun view(viewing: Boolean) {
        if (this.viewing == viewing) return
        this.viewing = viewing
        if (viewing) requests.trySend(Unit)
    }

    /**
     * Writes [request] through the provider, its items named from the last read (nothing before
     * the first, nothing for the made-up items); the provider's change notification brings the
     * list's new state. A failure is reported, the list stays.
     */
    fun act(request: Request) {
        val method = request.act.method ?: return
        scope.launch(io) {
            val s = snapshot ?: return@launch
            if (s.demo) return@launch
            try {
                val keys = writeKeys(request, s, filter, search, reader.format().zone, account, searchIn)
                val written = keys.toHashSet()
                apiCalls(request.act, s.items.filter { ItemKey(it.accountId, it.sourceKey) in written }).takeIf { it.isNotEmpty() }?.let(bridge)
                keys.chunked(HubContract.KEYS_MAX).forEach { reader.write(method, it) }
            } catch (e: RuntimeException) {
                onError(e)
            }
        }
    }
}
