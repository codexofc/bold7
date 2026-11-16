package dev.bold7.launcher

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import dev.bold7.launcher.keys.LaunchByTypingSetting
import dev.bold7.launcher.connections.ConnectionsController
import dev.bold7.launcher.frames.FramesModel
import dev.bold7.launcher.home.HiddenAppsFile
import dev.bold7.launcher.home.HomeModel
import dev.bold7.launcher.hub.AndroidHubReader
import dev.bold7.launcher.hub.HubFeed
import dev.bold7.launcher.hub.ShowcaseHubReader
import dev.bold7.launcher.hub.SwitchedHubReader
import dev.bold7.launcher.home.LauncherAppsSource
import dev.bold7.launcher.home.LayoutFile
import dev.bold7.launcher.notices.NoticeNames
import dev.bold7.launcher.profiles.ProfileController
import dev.bold7.platform.RemoteLaunches
import dev.bold7.platform.ShowcaseMask
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.showcaseOn
import dev.bold7.platform.SystemConnections
import dev.bold7.platform.SystemRecentTasks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executor

/**
 * The launcher process's objects, built by hand (docs/ARCHITECTURE.md §5). Threads start on
 * first use: the process may run before unlock for the recents provider's service alone.
 */
class LauncherGraph(private val app: Context) {
    /** Window animations, nothing else: no animation waits on a busy thread (CONTRIBUTING.md). */
    val surface: Handler by lazy { looper("bold7-surface", Process.THREAD_PRIORITY_DISPLAY) }

    /** Binder calls and buffers that must stay off the main and surface threads. */
    val io: Handler by lazy { looper("bold7-io", Process.THREAD_PRIORITY_BACKGROUND) }

    /**
     * The home's io: LauncherApps, PackageManager and icon drawing, its callbacks included. A
     * thread of its own, so drawing every icon at start never delays the snapshots on [io];
     * a single one, which keeps HomeModel's work in order.
     */
    private val appsThread: Handler by lazy { looper("bold7-apps", Process.THREAD_PRIORITY_BACKGROUND) }

    // Coroutine dispatchers over the threads' executors: no kotlinx-coroutines-android just for
    // Dispatchers.Main.
    /** Where the home collects its state, and only renders it. */
    val main: CoroutineDispatcher by lazy { app.mainExecutor.asCoroutineDispatcher() }

    val appsDispatcher: CoroutineDispatcher by lazy { Executor { appsThread.post(it) }.asCoroutineDispatcher() }

    /** [io] as a dispatcher: the home's images, decoded at once, not behind the icons' load on the apps thread. */
    val ioDispatcher: CoroutineDispatcher by lazy { Executor { io.post(it) }.asCoroutineDispatcher() }

    val apps by lazy { LauncherAppsSource(app, appsThread) }

    /** Launch by Typing: read on [io] at the first use, the mode defaults to the search till then. */
    val typingMode by lazy { LaunchByTypingSetting(File(app.filesDir, "launch-by-typing")).also { io.post(it::load) } }

    /** Launches whose opening the launcher draws on [surface]. */
    val launches by lazy { RemoteLaunches(app, surface) }

    /**
     * The search's contacts and pictures, a thread of its own started by the
     * first search: none while the home is only shown.
     */
    val search: CoroutineDispatcher by lazy { looper("bold7-search", Process.THREAD_PRIORITY_BACKGROUND).let { h -> Executor { h.post(it) }.asCoroutineDispatcher() } }

    /** The search's pictures (glyphs, contact pictures, thumbnails), out of its sources' way. */
    val searchPictures: CoroutineDispatcher by lazy { looper("bold7-search-pictures", Process.THREAD_PRIORITY_BACKGROUND).let { h -> Executor { h.post(it) }.asCoroutineDispatcher() } }

    /**
     * Built with the first home: the process may run before unlock for the recents provider's service alone.
     * A failure of its work is logged and the home stays up.
     */
    val home by lazy {
        val logged = CoroutineExceptionHandler { _, e -> Log.e("Bold7/Home", "home work failed", e) }
        // Credential-protected storage: the home activity only starts once the user unlocked.
        val layout = LayoutFile { File(app.filesDir, "home-layout.tsv") }
        val hidden = HiddenAppsFile { File(app.filesDir, "hidden-apps.tsv") }
        HomeModel(apps, layout, hidden, appsDispatcher, CoroutineScope(SupervisorJob() + logged)) { Log.e("Bold7/Home", "home layout", it) }
    }

    /**
     * The recent tasks, on [io] with the other binder calls, for the app switcher
     *, which starts it when it shows: no task listener is registered otherwise.
     */
    val frames by lazy {
        val tasks = SystemRecentTasks(app, launches)
        FramesModel(tasks, Executor { io.post(it) }.asCoroutineDispatcher(), CoroutineScope(SupervisorJob()), { apps.appTable().nameForPackage(it) }) {
            Log.e("Bold7/Frames", "frames work failed", it)
        }
    }

    /**
     * The Hub's thread: the provider's reads and its change notifications (HubWatch), for the
     * banner's counts. Started by the first home.
     */
    val hubThread: Handler by lazy { looper("bold7-hub", Process.THREAD_PRIORITY_BACKGROUND) }
    val hubDispatcher: CoroutineDispatcher by lazy { Executor { hubThread.post(it) }.asCoroutineDispatcher() }
    val hubReader by lazy { AndroidHubReader(app) }

    /** The Hub's reader, or the showcase's while bold7_showcase is 1: the Hub is not read then. */
    private val feedReader by lazy {
        SwitchedHubReader({ showcaseOn(app) }, { hubReader }, ShowcaseHubReader { hubReader.format() })
    }

    /** The showcase's carrier and alarm for the banner and Manage Connections, null when it is off. */
    val showcaseMask: () -> ShowcaseMask? = { if (showcaseOn(app)) ShowcaseMask(Showcase.CARRIER, Showcase.alarm.on) else null }

    /**
     * The Hub's counts, read from apps/hub's provider, and the home's list of notifications
     *, each app under its home name. A failure of its work is logged, the home
     * and the list stay up.
     */
    val hub by lazy {
        val names = NoticeNames(
            app.getString(R.string.notices_messages),
            app.getString(R.string.notices_text_messages),
            app.getString(R.string.notices_phone),
            app.getString(R.string.notices_calendar),
            app.getString(R.string.notices_missed_call),
        )
        HubFeed(
            feedReader,
            hubDispatcher,
            CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> Log.e("Bold7/Hub", "hub work failed", e) }),
            names,
            { pkg -> home.state.value.apps.firstOrNull { it.packageName == pkg }?.label ?: hubReader.appLabel(pkg) },
        ) {
            Log.e("Bold7/Hub", "Hub list not worked out: ${it.javaClass.name}") // its message could hold a text
        }
    }

    /** The sound profiles, on [io] with the other binder calls. */
    internal val profiles by lazy { ProfileController(app, io) }

    /**
     * Manage Connections: Android's switches turned and read on [io], All Off's
     * memory kept for the process. Built with the first home, which has it read the connections.
     */
    internal val connections by lazy { ConnectionsController(SystemConnections(app, io, showcaseMask), Executor { io.post(it) }, app.mainExecutor) }

    private fun looper(name: String, priority: Int) = Handler(HandlerThread(name, priority).apply { start() }.looper)
}
