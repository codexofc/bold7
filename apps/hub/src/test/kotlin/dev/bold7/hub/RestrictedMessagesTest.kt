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
}
