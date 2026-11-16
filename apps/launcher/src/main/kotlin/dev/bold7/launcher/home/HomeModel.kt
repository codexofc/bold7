package dev.bold7.launcher.home

import dev.bold7.icons.AppTable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** LauncherApps as the home needs it; [LauncherAppsSource] on the phone, a fake in the tests. */
interface AppSource {
    /** Every launchable activity of the user, icons ready to show. Blocking binder calls: io only. */
    fun load(): List<App>

    /** How each app appears (bbos7_apps.tsv): its grid name, already in [load]'s labels, and whether the grid hides it. Io only. */
    fun appTable(): AppTable

    /** The launchable activities of [packageName] now, none once it is removed, icons ready. Blocking: io only. */
    fun activitiesOf(packageName: String): List<App>

    /**
     * From now on, [onChange] gets the name of each package installed, updated, made unavailable
     * or removed, and true only when it is uninstalled. Io only.
     */
    fun watch(onChange: (packageName: String, removed: Boolean) -> Unit)
}

/** What of the state is saved. */
private val HomeState.saved get() = SavedHome(layout, favourites, frequent)

/**
 * The home's state, its loading and its updates. Every [source] and [layout] call runs on [io],
 * which must run one task at a time, in order (a single thread): the watch starts before the
 * full load, and a change it reports queues behind that load, so a package installed while the
 * list is read is never overwritten by it. The layout is read once with the apps, and written
 * each time it changes after that: a move once dropped or put back, a rename, an app opened
 * (Frequent), an uninstall, never during a move, never for the load itself. A failed read or write goes to [onError] and
 * the home goes on, in the order it has; after a failed read, the unreadable file is set aside
 * and nothing is written for the rest of the process, so a layout the next run may still read is
 * never overwritten by the alphabetical one. The user's choices of apps to hide or show ([hidden]) are
 * read on io with the layout, before the apps; an unreadable file is set aside, and if it cannot
 * be, the choices of the session stay in memory, never written over it.
 */
class HomeModel(
    private val source: AppSource,
    private val layout: LayoutFile,
    private val hidden: HiddenAppsFile,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val onError: (IOException) -> Unit,
) {
    private val mutable = MutableStateFlow(HomeState())

    /** False after a failed read: set on io before the load, read on io by every write. */
    private var writable = true

    /** False after a failed read of the user's choices whose file could not be set aside: never written over. Io only. */
    private var choicesWritable = true

    /** Collected on the main thread, which only renders it. */
    val state: StateFlow<HomeState> = mutable.asStateFlow()

    init {
        scope.launch(io) {
            source.watch { pkg, removed -> scope.launch(io) { dispatch(HomeEvent.PackageChanged(pkg, source.activitiesOf(pkg), removed)) } }
            val saved = try {
                layout.read()
            } catch (e: IOException) {
                onError(e)
                writable = false
                layout.setAside()
                SavedHome()
            }
            val choices = try {
                hidden.read()
            } catch (e: IOException) {
                onError(e)
                choicesWritable = hidden.setAside()
                emptyMap()
            }
            dispatch(HomeEvent.AppsLoaded(source.load(), saved, Hiding(source.appTable(), choices)))
        }
    }

    /** From the main thread (the user) or io (the system). */
    fun dispatch(event: HomeEvent) {
        while (true) {
            val before = mutable.value
            val after = reduce(before, event)
            if (!mutable.compareAndSet(before, after)) continue
            // A move is written once, dropped or put back; not at each step.
            if (before.loaded && after.moving == null && (after.saved != before.saved || before.moving != null)) scope.launch(io) { save() }
            return
        }
    }

    /**
     * Hides [app] from the grid, or shows it again: the user's choice over the table's, kept in
     * files/hidden-apps.tsv. From the main thread; the work is on io. For a page that lists [HomeState.hiddenApps].
     */
    fun setHidden(app: App, hide: Boolean) {
        scope.launch(io) {
            val before = mutable.value.hiding
            val after = before.with(app, hide)
            if (after == before) return@launch
            dispatch(HomeEvent.HidingChanged(after))
            if (!choicesWritable) return@launch
            try {
                hidden.write(after.choices)
            } catch (e: IOException) {
                onError(e)
            }
        }
    }

    // The latest home, whichever change asked for the write: two quick changes end on the last.
    private fun save() {
        if (!writable) return
        try {
            layout.write(mutable.value.saved)
        } catch (e: IOException) {
            onError(e)
        }
    }
}
