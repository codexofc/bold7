package dev.bold7.hub

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RestrictedMessagesTest {
    @Test fun aLineNamesAnSmsOrAnMmsRow() {
        assertEquals("content://sms" to 42L, liftedRow("content://sms 42"))
        assertEquals("content://mms" to 7L, liftedRow("content://mms 7\r"))
    }

    @Test fun anythingElseIsNotWritten() {
        // Only these two tables, only the restriction, by one _id: never a where of another shape.
        assertNull(liftedRow("content://contacts 42"))
        assertNull(liftedRow("content://sms 42 OR 1=1"))
        assertNull(liftedRow("content://sms -1"))
        assertNull(liftedRow("content://sms"))
        assertNull(liftedRow(""))
    }

    @Test fun theUpdateLiftsTheRestrictionOfThatRowOnly() {
        assertContentEquals(
            arrayOf("update", "--uri", "content://mms", "--bind", "restricted:i:0", "--where", "_id=7"),
            liftArgs("content://mms", 7),
        )
    }

    private class Busy : RuntimeException("database is locked (code 5 SQLITE_BUSY)")

    @Test fun aLockedRowIsTriedAgainThenLifted() {
        var calls = 0
        val waits = mutableListOf<Int>()
        // As ContentProviderProxy throws it, wrapped by the reflection that called update.
        val done = liftRetrying(tries = 5, pause = { waits += it }) {
            calls++
            if (calls < 3) throw java.lang.reflect.InvocationTargetException(Busy())
        }
        assertEquals(true, done)
        assertEquals(3, calls)
        assertEquals(listOf(1, 2), waits)
    }

    @Test fun aRowStillLockedIsLeftNotTheWholeRun() {
        var calls = 0
        val done = liftRetrying(tries = 4, pause = {}) { calls++; throw java.lang.reflect.InvocationTargetException(Busy()) }
        assertEquals(false, done)
        assertEquals(4, calls)
    }

    @Test fun anotherFailureIsLeftAtOnce() {
        var calls = 0
        val done = liftRetrying(tries = 4, pause = {}) { calls++; throw IllegalStateException("no such row") }
        assertEquals(false, done)
        assertEquals(1, calls)
    }
}
