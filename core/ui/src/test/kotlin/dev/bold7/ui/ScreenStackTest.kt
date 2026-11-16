package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScreenStackTest {
    @Test fun `an app starts on its root screen`() {
        val stack = ScreenStack("list")
        assertEquals("list", stack.top)
        assertEquals(true, stack.atRoot)
    }

    @Test fun `a screen pushed is on top, and back returns to the one under it`() {
        val stack = ScreenStack("list").push("detail").push("edit")
        assertEquals("edit", stack.top)
        assertEquals(false, stack.atRoot)
        assertEquals(listOf("list", "detail"), stack.pop()?.screens)
        assertEquals("list", stack.pop()?.pop()?.top)
    }

    @Test fun `back on the root leaves the app`() {
        assertNull(ScreenStack("list").pop())
        assertNull(ScreenStack("list").push("detail").pop()?.pop())
    }

    @Test fun `the same screen can be stacked twice, as two pages of the same kind`() {
        assertEquals(listOf(1, 2, 2), ScreenStack(1).push(2).push(2).screens)
    }

    @Test fun `a stack is never empty`() {
        assertFailsWith<IllegalArgumentException> { ScreenStack(emptyList<String>()) }
    }
}
