package dev.bold7.launcher.frames

import dev.bold7.launcher.frames.FramesEvent.Closed
import dev.bold7.launcher.frames.FramesEvent.Synced
import dev.bold7.design.Dimens
import dev.bold7.platform.RecentTask
import kotlin.test.Test
import kotlin.test.assertEquals

class FramesReducerTest {
    private fun task(id: Int, lastActive: Long = 0L) = RecentTask(id, "app.$id", "App $id", lastActive)

    private fun tasks(vararg ids: Int) = ids.map(::task)

    private fun FramesState.ids() = frames.map { it.taskId }

    private val max = Dimens.FRAMES_MAX

    @Test fun `frames follow the system's order, most recently used first`() {
        val s = reduce(FramesState(), Synced(tasks(3, 1, 2)))
        assertEquals(listOf(3, 1, 2), s.ids())
        assertEquals("App 3", s.frames[0].label)
    }

    @Test fun `no more frames than the cap, the oldest task left out`() {
        val ids = (max + 1 downTo 1).toList()
        val s = reduce(FramesState(), Synced(ids.map(::task)))
        assertEquals(ids.take(max), s.ids())
    }

    @Test fun `the same sync twice changes nothing, so nothing is redrawn`() {
        val s = reduce(FramesState(), Synced(tasks(1, 2)))
        assertEquals(s, reduce(s, Synced(tasks(1, 2))))
    }

    @Test fun `a task the system no longer lists loses its frame`() {
        val s = reduce(reduce(FramesState(), Synced(tasks(1, 2, 3))), Synced(tasks(1, 3)))
        assertEquals(listOf(1, 3), s.ids())
    }

    @Test fun `a closed frame goes at once, and the next task takes its place at the next sync`() {
        val all = (max + 1 downTo 1).toList()
        val closed = reduce(reduce(FramesState(), Synced(all.map(::task))), Closed(all[2]))
        assertEquals(all.take(max + 1).filter { it != all[2] }.take(max - 1), closed.ids())

        // The system has not removed it yet: a sync still lists it, the frame stays closed.
        val again = reduce(closed, Synced(all.map(::task)))
        assertEquals(all.filter { it != all[2] }.take(max), again.ids())
        assertEquals(setOf(all[2]), again.closing)

        // Removed: the system no longer lists it, the closing mark goes.
        val gone = reduce(again, Synced(all.filter { it != all[2] }.map(::task)))
        assertEquals(emptySet(), gone.closing)
        assertEquals(again.frames, gone.frames)
    }

    @Test fun `a frame remembers when its task was last used and its app's package`() {
        val s = reduce(FramesState(), Synced(listOf(task(1, lastActive = 5L))))
        assertEquals(5L, s.frames[0].lastActive)
        assertEquals("app.1", s.frames[0].packageName)
    }

    @Test fun `shown tasks skip the closing ones before the cap`() {
        val all = (max + 1 downTo 1).toList()
        assertEquals(all.filter { it != all[1] }.take(max), shown(all.map(::task), setOf(all[1])).map { it.taskId })
    }
}
