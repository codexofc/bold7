package dev.bold7.messages

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.showcase.showcaseEvent
import dev.bold7.showcase.showcaseOn
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

class MessagesApp : Application() {
    val graph by lazy { MessagesGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        super.onCreate()
    }
}

/** The objects of Messages' process, built by hand (docs/ARCHITECTURE.md §5). */
class MessagesGraph(app: Application) {
    val main: CoroutineDispatcher = app.mainExecutor.asCoroutineDispatcher()

    /** The provider's reads and writes, its change notifications, the images' decoding: never the main thread. */
    val ioThread: Handler = Handler(HandlerThread("bold7-messages", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    val io: CoroutineDispatcher = Executor { ioThread.post(it) }.asCoroutineDispatcher()

    /** apps/hub's mail: its calls on [io] only. */
    val mail = MailClient(app)

    /** Messages' options and saved messages: read on [io] first, written there only. */
    val store = MessagesStore(app).also { ioThread.post { it.load() } }

    /** The drafts kept on the phone, shown in the inbox: its reads and writes on [io] only. */
    val drafts: Drafts = Drafts(app) { feed.reload() }

    val reader = AndroidInboxReader(app, drafts, store)

    /** Google Messages' API: its calls on [io] only. */
    val api = GrpcMessagingApi(app)
    val threads = AndroidThreadReader(app)

    /** To's address book and the band's picture: its reads on [io] only. */
    val contacts = AndroidContactBook(app)

    /** The files attached in the thread's field: on [io] only; those of a process gone are dropped first. */
    val attachments = AndroidAttachmentStore(app).also { store -> ioThread.post { store.clear() } }

    /** The work of the feed and of the threads: a failure is logged, the screen stays up. */
    val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> Log.e(TAG, "messages work failed", e) })

    /** The inbox's lines: the showcase's while bold7_showcase is 1 (read at each load, on [io]), the Hub's otherwise. */
    val feed = Feed(
        SwitchedInbox({ showcaseOn(app) }, { reader }, ShowcaseInbox({ showcaseEvent(app) }) { inboxFormat(app) }), io, scope,
        onError = { Log.e(TAG, "inbox not worked out: ${it.javaClass.name}") }, // its message could hold a text
        bridge = { calls -> calls.forEach { call -> apiCall(call) } },
    )

    /** A call of the inbox to Google Messages: refused, it is logged; the Hub's state stays the user's. */
    private fun apiCall(call: ApiCall) = try {
        api.run(call)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Google Messages refused ${call.javaClass.simpleName}: ${(e as? MessagingApiException)?.code ?: e.javaClass.name}")
    }

    /** A thread's effects, for one activity. */
    fun conversation(scope: CoroutineScope) = Conversation(threads, api, contacts, attachments, io, scope) {
        Log.w(TAG, "thread call failed: ${(it as? MessagingApiException)?.code ?: it.javaClass.name}")
    }
}
