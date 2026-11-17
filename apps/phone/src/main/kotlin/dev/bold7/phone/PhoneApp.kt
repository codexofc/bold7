package dev.bold7.phone

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.showcase.avatar
import dev.bold7.showcase.showcaseOn
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow

internal const val TAG = "Bold7/Phone"

class PhoneApp : Application() {
    val graph by lazy { PhoneGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        super.onCreate()
    }
}

/** The objects of the Phone's process, built by hand (docs/ARCHITECTURE.md §5). */
class PhoneGraph(private val app: Application) {
    val main: CoroutineDispatcher = app.mainExecutor.asCoroutineDispatcher()

    /** The call log, the contacts' photos, AudioManager and TelephonyManager reads, placing a call: never the main thread. */
    val ioThread: Handler = Handler(HandlerThread("bold7-phone", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    val io: CoroutineDispatcher = Executor { ioThread.post(it) }.asCoroutineDispatcher()

    /**
     * Telecom's calls as our InCallService last saw them, and what the user did on the call screen.
     * Written on the main thread only, by the service (Telecom's callbacks) and the call screen.
     */
    val board = MutableStateFlow(CallBoard())

    /** Telecom while it binds our InCallService (we hold DIALER and a call exists); null otherwise. Main thread. */
    var telecom: Telecom? = null

    /** Whether the call screen is on the screen: the incoming call's fallback notification waits for it (Bold7InCallService). */
    var callScreenShown = false

    /** The missed calls' dialog (sim-19): fed by the InCallService, closed by PromptActivity. Main thread. */
    var missed = Missed()

    private val realLog by lazy { AndroidCallLog(app) }
    private val realSpeedDial by lazy { SpeedDialStore(app) }
    private val realTimers by lazy { TimersStore(app) }

    /**
     * The call log, or the showcase's made-up one while the global setting bold7_showcase is 1
     * (dev.bold7.showcase): asked again at every use, on [io] only.
     */
    val log: CallLogSource get() = callLog(showcaseOn(app), { realLog }, avatar = { avatar(app, it, AVATAR_PX) })

    /** The speed dial keys, read and written on [io]. */
    val speedDial: Kept<SpeedDial> get() = speedDials(showcaseOn(app)) { realSpeedDial }

    /** Status' cleared timers, read and written on [io]. */
    val timers: Kept<CallTimersCleared> get() = timerStore(showcaseOn(app)) { realTimers }
}

/** A showcase avatar's size on the call screen: a contact thumbnail's (96 px). */
private const val AVATAR_PX = 96
