package dev.bold7.launcher.frames

import dev.bold7.platform.OpeningAnimation
import dev.bold7.platform.RecentTask
import dev.bold7.platform.RecentTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The system's recents in memory: [used] and [removeBySystem] call back like the system does.
 * [failing] names the calls that throw once, as an unexpected system error would.
 */
private class FakeRecentTasks : RecentTasks {
    val tasks = mutableListOf<RecentTask>()
    val opened = mutableListOf<Int>()
    val removed = mutableListOf<Int>()
    val failing = mutableSetOf<String>()
    var lists = 0
    var openSucceeds = true
    var watchers = 0
    private var clock = 100L
    private var onRemoved: ((Int) -> Unit)? = null

    private fun maybeFail(call: String) {
        if (failing.remove(call)) throw IllegalArgumentException("$call failed")
    }

    override fun list(max: Int): List<RecentTask> {
        maybeFail("list")
        lists++
        return tasks.take(max)
    }

    override fun open(taskId: Int, animation: OpeningAnimation?): Boolean {
        maybeFail("open")
        opened += taskId
        return openSucceeds
    }

    override fun remove(taskId: Int): Boolean {
        removed += taskId
        return tasks.removeAll { it.taskId == taskId }
    }

    override fun watch(onRemoved: (Int) -> Unit): AutoCloseable {
        watchers++
        this.onRemoved = onRemoved
        return AutoCloseable {
            watchers--
            this.onRemoved = null
        }
    }

    fun add(vararg ids: Int) = apply {
        ids.forEach { tasks += RecentTask(it, "app.$it", "App $it", clock++) }
    }

    /** [taskId] was used: it is now the most recent task. */
    fun used(taskId: Int) {
        tasks.removeAll { it.taskId == taskId }
        tasks.add(0, RecentTask(taskId, "app.$taskId", "App $taskId", clock++))
    }

    fun removeBySystem(taskId: Int) {
        tasks.removeAll { it.taskId == taskId }
        onRemoved?.invoke(taskId)
    }
}

class FramesModelTest {
    private val errors = mutableListOf<Throwable>()

    /**
     * A model whose io work runs only when the test advances [TestScope.testScheduler], in a
     * supervisor scope like the launcher's: a failure reaches [errors], not the test.
     */
    private fun TestScope.model(source: FakeRecentTasks, nameOf: (String) -> String? = { null }) =
        FramesModel(source, StandardTestDispatcher(testScheduler), CoroutineScope(SupervisorJob()), nameOf, errors::add)

    // a frame wears the name its app has on the grid (bbos7_apps.tsv), else the task's.
    @Test fun `a frame takes its app's grid name`() = runTest {
        val source = FakeRecentTasks().add(1, 2)
        val model = model(source) { pkg -> "Browser".takeIf { pkg == "app.1" } }
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("Browser", "App 2"), model.state.value.frames.sortedBy { it.taskId }.map { it.label })
    }

    private fun FramesModel.ids() = state.value.frames.map { it.taskId }

    private fun TestScope.started(source: FakeRecentTasks) = model(source).also {
        it.start()
        testScheduler.advanceUntilIdle()
    }

    @Test fun `the tasks are listed from the system on io, the listener registered there`() = runTest {
        val source = FakeRecentTasks().add(1, 2).apply { tasks.reverse() }
        val model = model(source)
        model.start()
        assertEquals(emptyList(), model.ids())
        assertEquals(0, source.watchers) // registering is a binder call too

        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2, 1), model.ids())
        assertEquals(1, source.watchers)
    }

    @Test fun `no listener once the last switcher is gone, and one closing does not cut the next`() = runTest {
        val source = FakeRecentTasks().add(1)
        val model = started(source)
        model.start() // a second switcher opens while the first still shows
        testScheduler.advanceUntilIdle()
        model.stop() // the first one closes
        testScheduler.advanceUntilIdle()
        assertEquals(1, source.watchers)

        model.stop()
        testScheduler.advanceUntilIdle()
        assertEquals(0, source.watchers)

        model.stop() // a stray stop changes nothing
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1, source.watchers)
    }

    @Test fun `returning to a switcher syncs again from the system`() = runTest {
        val source = FakeRecentTasks().add(1)
        val model = started(source)
        model.stop()
        testScheduler.advanceUntilIdle()
        assertEquals(0, source.watchers)

        source.used(2) // not heard: no listener
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1), model.ids())

        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2, 1), model.ids())
    }

    @Test fun `removals pushed together lead to a single sync`() = runTest {
        val source = FakeRecentTasks().add(1, 2, 3)
        val model = started(source)
        val lists = source.lists

        source.removeBySystem(1)
        source.removeBySystem(2)
        testScheduler.advanceUntilIdle()
        assertEquals(lists + 1, source.lists)
        assertEquals(listOf(3), model.ids())
    }

    @Test fun `a closed frame goes at once, then the system removes its task`() = runTest {
        val source = FakeRecentTasks().add(2, 1)
        val model = started(source)

        model.close(2)
        assertEquals(listOf(1), model.ids())
        assertEquals(emptyList(), source.removed)

        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2), source.removed)
        assertEquals(listOf(1), model.ids())
    }

    @Test fun `a task removed by the system loses its frame`() = runTest {
        val source = FakeRecentTasks().add(2, 1)
        val model = started(source)
        source.removeBySystem(1)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2), model.ids())
    }

    @Test fun `opening a task tells when the system has answered, and one it refuses is dropped`() = runTest {
        val source = FakeRecentTasks().add(2, 1)
        val model = started(source)
        var answered = 0
        model.open(1) { answered++ }
        assertEquals(0, answered) // the call is on io
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1), source.opened)
        assertEquals(1, answered)

        source.tasks.removeAll { it.taskId == 2 } // gone meanwhile, without a callback
        source.openSucceeds = false
        model.open(2) { answered++ }
        testScheduler.advanceUntilIdle()
        assertEquals(2, answered)
        assertEquals(listOf(1), model.ids())
    }

    @Test fun `a system error is reported and the frames keep working`() = runTest {
        val source = FakeRecentTasks().add(1)
        source.failing += "list"
        val model = started(source)
        assertEquals(1, errors.size)
        assertEquals(emptyList(), model.ids())

        // The switcher still closes after a failed open.
        source.failing += "open"
        var answered = false
        model.open(1) { answered = true }
        testScheduler.advanceUntilIdle()
        assertEquals(2, errors.size)
        assertEquals(true, answered)

        model.start() // the next switcher syncs as usual
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1), model.ids())
    }
}
