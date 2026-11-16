package dev.bold7.launcher.home

import kotlinx.coroutines.test.StandardTestDispatcher
import dev.bold7.icons.AppTable
import dev.bold7.icons.parseAppTable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** LauncherApps as a list in memory: [install] and [uninstall] call back like the system does. */
private class FakeApps(vararg apps: App) : AppSource {
    val installed = apps.toMutableList()
    var table = AppTable(emptyMap())
    var loads = 0
    val queried = mutableListOf<String>()
    var watching = false

    /** Runs inside [load], after the list is read: a package installed while the system answers. */
    var duringLoad: () -> Unit = {}
    private var onChange: ((String, Boolean) -> Unit)? = null

    override fun load(): List<App> {
        loads++
        return installed.toList().also { duringLoad() }
    }

    override fun appTable(): AppTable = table

    override fun activitiesOf(packageName: String): List<App> {
        queried += packageName
        return installed.filter { it.packageName == packageName }
    }

    override fun watch(onChange: (String, Boolean) -> Unit) {
        watching = true
        this.onChange = onChange
    }

    fun install(app: App) {
        installed += app
        onChange?.invoke(app.packageName, false)
    }

    fun uninstall(packageName: String) {
        installed.removeAll { it.packageName == packageName }
        onChange?.invoke(packageName, true)
    }

    /** Still installed, nothing to launch any more: disabled, or on storage that went away. */
    fun disable(packageName: String) {
        installed.removeAll { it.packageName == packageName }
        onChange?.invoke(packageName, false)
    }
}

/** The layout file in a fresh directory, counting its writes, and on which thread they ran. */
private class CountingLayout(dir: File = Files.createTempDirectory("home").toFile()) {
    val file = File(dir, "home.tsv")
    var writes = 0
    var failNext = false
    val layout = object : LayoutFile({ file }) {
        override fun write(home: SavedHome) {
            if (failNext) { failNext = false; throw IOException("disk full") }
            writes++
            super.write(home)
        }
    }
}

class HomeModelTest {
    private val agenda = App("agenda", "agenda.Main", "Agenda")
    private val maps = App("maps", "maps.Main", "Maps")
    private val zoom = App("zoom", "zoom.Main", "Zoom")

    private val errors = mutableListOf<Exception>()

    /** A model whose io work runs only when the test advances [TestScope.testScheduler]. */
    private fun TestScope.model(source: AppSource, layout: CountingLayout = CountingLayout(), hidden: File = hiddenFile()) =
        HomeModel(source, layout.layout, HiddenAppsFile { hidden }, io = StandardTestDispatcher(testScheduler), scope = this) { errors += it }

    private fun hiddenFile() = File(Files.createTempDirectory("hidden").toFile(), "hidden-apps.tsv")

    @Test fun `apps load on the io dispatcher, not on the caller's thread`() = runTest {
        val source = FakeApps(zoom, agenda)
        val model = model(source)
        assertEquals(emptyList(), model.state.value.apps)
        assertEquals(0, source.loads)
        assertEquals(false, source.watching) // registering is a binder call too

        testScheduler.advanceUntilIdle()
        assertEquals(listOf(agenda, zoom), model.state.value.apps)
    }

    @Test fun `an installed app is added without reloading the list`() = runTest {
        val source = FakeApps(agenda, zoom)
        val model = model(source)
        testScheduler.advanceUntilIdle()

        source.install(maps)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(agenda, maps, zoom), model.state.value.apps)
        assertEquals(1, source.loads)
        assertEquals(listOf("maps"), source.queried)
    }

    @Test fun `an uninstalled app leaves the grid`() = runTest {
        val source = FakeApps(agenda, zoom)
        val model = model(source)
        testScheduler.advanceUntilIdle()

        source.uninstall("zoom")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(agenda), model.state.value.apps)
    }

    @Test fun `a package installed while the list loads is not lost`() = runTest {
        val source = FakeApps(agenda)
        source.duringLoad = { source.duringLoad = {}; source.install(maps) }
        val model = model(source)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(agenda, maps), model.state.value.apps)
    }

    @Test fun `events from the view go through the reducer`() = runTest {
        val model = model(FakeApps(agenda))
        testScheduler.advanceUntilIdle()
        model.dispatch(HomeEvent.ShowPanel(Panel.MEDIA))
        model.dispatch(HomeEvent.Expand(true))
        assertEquals(Panel.MEDIA, model.state.value.panel)
        model.dispatch(HomeEvent.HomeKey)
        assertEquals(false, model.state.value.expanded)
    }

    @Test fun `what was opened survives a restart`() = runTest {
        val layout = CountingLayout()
        val first = model(FakeApps(agenda, maps, zoom), layout)
        testScheduler.advanceUntilIdle()
        first.dispatch(HomeEvent.Launched(zoom.component))
        testScheduler.advanceUntilIdle()

        val second = model(FakeApps(agenda, maps, zoom), layout)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<Tile>(zoom), second.state.value.items(Panel.FREQUENT))
    }

    @Test fun `one write per change kept, none for a view or the tray`() = runTest {
        val layout = CountingLayout()
        val model = model(FakeApps(agenda, maps, zoom), layout)
        testScheduler.advanceUntilIdle()
        val atLoad = layout.writes
        assertEquals(0, atLoad) // loading writes nothing
        model.dispatch(HomeEvent.ShowPanel(Panel.MEDIA))
        model.dispatch(HomeEvent.Expand(true))
        testScheduler.advanceUntilIdle()
        assertEquals(atLoad, layout.writes)
        model.dispatch(HomeEvent.Launched(maps.component))
        testScheduler.advanceUntilIdle()
        assertEquals(atLoad + 1, layout.writes)
    }

    @Test fun `the layout is read and written on io only`() = runTest {
        val layout = CountingLayout()
        layout.file.writeText("page\napp\tzoom/zoom.Main\n")
        val model = model(FakeApps(agenda, zoom), layout)
        assertEquals(0, layout.writes)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<Tile>(zoom, agenda), model.state.value.all)
    }

    @Test fun `a failed write is reported, the home goes on`() = runTest {
        val layout = CountingLayout()
        layout.failNext = true
        val model = model(FakeApps(agenda, zoom), layout)
        testScheduler.advanceUntilIdle()
        model.dispatch(HomeEvent.Launched(zoom.component))
        testScheduler.advanceUntilIdle()
        assertEquals(1, errors.size)
        assertEquals(listOf<Tile>(zoom), model.state.value.items(Panel.FREQUENT))
    }

    @Test fun `after a failed read nothing is written all session, and the file is set aside`() = runTest {
        val layout = CountingLayout()
        layout.file.mkdir() // reading a directory fails
        val model = model(FakeApps(agenda, zoom), layout)
        testScheduler.advanceUntilIdle()
        assertEquals(1, errors.size)
        assertEquals(listOf<Tile>(agenda, zoom), model.state.value.all)
        model.dispatch(HomeEvent.Launched(zoom.component))
        testScheduler.advanceUntilIdle()
        assertEquals(0, layout.writes)
        assertTrue(File(layout.file.path + ".bad").isDirectory)
    }

    @Test fun `an uninstalled app leaves the layout, a disabled one keeps its place`() = runTest {
        val layout = CountingLayout()
        val source = FakeApps(agenda, maps, zoom)
        val model = model(source, layout)
        testScheduler.advanceUntilIdle()
        source.disable("maps")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<Tile>(agenda, zoom), model.state.value.all)
        assertEquals(3, model.state.value.layout.size)
        source.uninstall("zoom")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("agenda", "maps"), model.state.value.layout.map { (it as App).packageName })
        assertEquals(1, layout.writes) // the uninstall; hiding Maps changed nothing to keep
    }

    // the table's defaults, the user's choices over them, kept in their own file.
    @Test fun `the table hides an app, the choices file shows it again`() = runTest {
        val source = FakeApps(agenda, maps, zoom).apply { table = parseAppTable("maps\t*\t-\thidden\tD1\nzoom\t*\t-\thidden\tD1") }
        val hidden = hiddenFile().apply { writeText("zoom/zoom.Main\tshown\n") }
        val model = model(source, hidden = hidden)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<Tile>(agenda, zoom), model.state.value.all)
        assertEquals(listOf(maps), model.state.value.hiddenApps)
    }

    @Test fun `a choice is written on io and survives a restart`() = runTest {
        val hidden = hiddenFile()
        val model = model(FakeApps(agenda, maps, zoom), hidden = hidden)
        testScheduler.advanceUntilIdle()
        model.setHidden(maps, true)
        assertEquals(false, hidden.exists())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<Tile>(agenda, zoom), model.state.value.all)
        assertEquals("maps/maps.Main\thidden\n", hidden.readText())

        val second = model(FakeApps(agenda, maps, zoom), hidden = hidden)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(maps), second.state.value.hiddenApps)
        second.setHidden(maps, false)
        testScheduler.advanceUntilIdle()
        // Hidden since this run started, so never placed: it joins the end of the grid.
        assertEquals(listOf<Tile>(agenda, zoom, maps), second.state.value.all)
        assertEquals("", hidden.readText())
    }

    @Test fun `an unreadable choices file is set aside, the table alone decides`() = runTest {
        val source = FakeApps(agenda, maps).apply { table = parseAppTable("maps\t*\t-\thidden\tD1") }
        val hidden = hiddenFile().apply { mkdir() }
        val model = model(source, hidden = hidden)
        testScheduler.advanceUntilIdle()
        assertEquals(1, errors.size)
        assertEquals(listOf<Tile>(agenda), model.state.value.all)
        assertTrue(File(hidden.path + ".bad").isDirectory)
    }

    // The unreadable file could not be set aside: a write would replace it, so none this run.
    @Test fun `a choices file neither read nor set aside is never written over`() = runTest {
        val hidden = hiddenFile().apply { mkdir() }
        File(hidden.path + ".bad").apply { mkdir() }.resolve("kept").writeText("x") // a full directory refuses the rename
        val model = model(FakeApps(agenda, maps), hidden = hidden)
        testScheduler.advanceUntilIdle()
        model.setHidden(maps, true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<Tile>(agenda), model.state.value.all)
        assertEquals(1, errors.size) // the read; no write tried
        assertTrue(hidden.isDirectory)
    }
}
