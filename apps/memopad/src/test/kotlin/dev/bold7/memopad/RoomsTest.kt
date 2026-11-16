package dev.bold7.memopad

import dev.bold7.ui.fit
import kotlin.test.Test
import kotlin.test.assertTrue

class RoomsTest {
    private val px = { s: String -> s.length * 14f }

    @Test fun aLabelEndsBeforeItsValueAndTheValueStaysInItsHalf() {
        for (width in listOf(480, 710)) {
            val value = fit("1234567890123456789", Rooms.value(width), px)
            assertTrue(px(value) <= Rooms.value(width))
            val label = fit("Number of Entries with a very long translated label:", Rooms.label(width, px(value)), px)
            assertTrue(px(label) + px(value) < width, "$width: $label")
        }
    }
}
