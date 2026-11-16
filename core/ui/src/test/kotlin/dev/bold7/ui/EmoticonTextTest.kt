package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmoticonTextTest {
    // Every character 10 px wide; an image 23 px at a 23 px text.
    private val measure = { s: String -> 10f * s.codePointCount(0, s.length) }
    private val size = 23f
    private val smile = "🙂"
    private val wink = "😉"
    private val heart = "❤️"

    @Test fun aTextIsCutInTextAndImagesInOrder() {
        val c = emoticonChunks("Salut $smile et $heart.")
        assertEquals(listOf("Salut ", smile, " et ", heart, "."), c.map { it.text })
        assertEquals(listOf(-1, 0, -1, 14, -1), c.map { it.emoticon })
        assertTrue(emoticonChunks("").isEmpty())
        assertFalse(emoticonChunks("abc").single().isImage)
        // Shortcuts stay text in a list (emojis only), image when asked.
        assertFalse(emoticonChunks("a :) b").any { it.isImage })
        assertTrue(emoticonChunks("a :) b", shortcuts = true).any { it.isImage })
    }

    @Test fun anImageCountsItsWidthNotItsCharacters() {
        // "ab" 20 + image 23 + "cd" 20; the emoji alone would be 10 as one code point.
        assertEquals(63f, emoticonAdvance("ab${smile}cd", size, measure))
        assertEquals(40f, emoticonAdvance("abcd", size, measure))
        // The size scales it: a 27 px text draws 27 px images.
        assertEquals(27f, emoticonWidth(27f))
        assertEquals(23f, emoticonWidth(23f))
    }

    @Test fun aTextThatFitsIsKeptWhole() {
        assertEquals("ab$smile", fitEmoticons("ab$smile", 43f, size, measure))
    }

    @Test fun aCutKeepsAnImageWholeOrDropsItAndNeverSplitsIt() {
        val text = "ab${smile}cdefgh"
        // Room for "ab" + image + "…" (20 + 23 + 10 = 53): the image stays.
        assertEquals("ab$smile…", fitEmoticons(text, 53f, size, measure))
        // One pixel less: the image goes with the rest, "ab…".
        assertEquals("ab…", fitEmoticons(text, 52f, size, measure))
        // Whatever the room, the result fits and no image is cut: every emoji in it is whole.
        for (room in 0..120) {
            val out = fitEmoticons(text, room.toFloat(), size, measure)
            assertTrue(emoticonAdvance(out, size, measure) <= room, "room $room: $out")
            assertTrue(!out.contains('\uD83D') || out.contains(smile), "room $room: $out")
        }
    }

    @Test fun twoImagesAndTheVariationSelectorAreCountedOnce() {
        // heart with its U+FE0F is one image: 23 + 23 + the space's 10.
        assertEquals(56f, emoticonAdvance("$heart$wink ", size, measure))
    }
}
