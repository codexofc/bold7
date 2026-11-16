package dev.bold7.memopad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemosTest {
    private val now = 1_800_000_000_000L
    private val showcase = memosFor(showcase = true, now = now) { error("real source opened") }

    @Test
    fun theShowcaseNeverOpensTheRealMemos() {
        assertTrue(showcase.all().isNotEmpty())
    }

    @Test
    fun theListAndAMemoAreServed() {
        val all = showcase.all()
        val packing = all.single { it.title == "Hike packing list" }
        assertTrue("Rain jacket" in packing.body)
        assertEquals(now - 3 * 3_600_000L, packing.modified)
        assertEquals(packing, showcase.get(packing.id))
        assertEquals(null, showcase.get(999))
    }

    @Test
    fun writesDoNotEscape() {
        val before = showcase.all()
        val id = before.first().id
        assertEquals(id, showcase.save(id, "Changed", "Changed"))
        assertEquals(null, showcase.save(null, "New", "New"))
        assertFalse(showcase.delete(id))
        assertEquals(before, showcase.all())
    }

    @Test
    fun withoutTheShowcaseTheRealSourceIsUsed() {
        val real = memosFor(showcase = false, now = now) { showcase }
        assertEquals(showcase.all(), real.all())
    }
}
