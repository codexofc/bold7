package dev.bold7.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedCacheTest {
    @Test
    fun theLeastRecentlyUsedGoesFirstWhenOverBudget() {
        val c = BoundedCache<String, Int>(10) { it }
        c.put("a", 4)
        c.put("b", 4)
        c["a"] // a is now the most recent
        c.put("c", 4)
        assertNull(c["b"])
        assertEquals(4, c["a"])
        assertEquals(4, c["c"])
        assertEquals(8, c.size)
    }

    @Test
    fun anEntryLargerThanTheBudgetIsNotKept() {
        val c = BoundedCache<String, Int>(10) { it }
        c.put("a", 3)
        c.put("big", 11)
        assertNull(c["big"])
        assertEquals(3, c["a"])
    }

    @Test
    fun replacingAnEntryCountsItOnce() {
        val c = BoundedCache<String, Int>(10) { it }
        c.put("a", 6)
        c.put("a", 7)
        assertEquals(7, c.size)
    }
}
