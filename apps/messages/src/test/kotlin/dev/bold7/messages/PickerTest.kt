package dev.bold7.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PickerTest {
    private fun item(name: String, dir: String) = MediaItem("content://m/$dir/$name", name, "image/jpeg", 10, dir)
    private val items = listOf(
        item("b.jpg", "DCIM/Camera"), item("a.jpg", "DCIM/Camera"), item("s.png", "Pictures/Screenshots"),
        item("x.jpg", "DCIM/Camera/Old"), item("top.jpg", ""),
    )

    @Test
    fun rootHoldsFoldersByNameThenItsFiles() {
        val rows = browse(items, "")
        assertEquals(listOf("DCIM", "Pictures"), rows.filterIsInstance<PickerRow.Folder>().map { it.name })
        assertEquals(3, (rows[0] as PickerRow.Folder).count)
        assertEquals("top.jpg", (rows.last() as PickerRow.File).item.name)
    }

    @Test
    fun aFolderHoldsItsSubfoldersThenItsFilesByName() {
        val rows = browse(items, "DCIM/Camera")
        assertEquals(PickerRow.Folder("Old", "DCIM/Camera/Old", 1), rows[0])
        assertEquals(listOf("a.jpg", "b.jpg"), rows.drop(1).map { (it as PickerRow.File).item.name })
    }

    @Test
    fun aFolderNamedLikeAnotherPrefixIsNotItsChild() {
        assertEquals(emptyList(), browse(listOf(item("a.jpg", "DCIM2")), "DCIM"))
    }

    @Test
    fun escapeGoesUpToTheFocusItCameFrom() {
        val n = PickerNav().focused(2).into("DCIM").focused(1).into("DCIM/Camera")
        assertEquals(PickerNav("DCIM", 1, listOf("" to 2)), n.up())
        assertEquals(PickerNav("", 2), n.up()!!.up())
        assertNull(n.up()!!.up()!!.up())
    }

    @Test
    fun titleAndSizes() {
        assertEquals("Picture", pickerTitle("Picture", ""))
        assertEquals("DCIM/Camera", pickerTitle("Picture", "DCIM/Camera"))
        assertEquals("999 B", sizeText(999))
        assertEquals("1.5 KB", sizeText(1536))
        assertEquals("2.0 MB", sizeText(2L * 1024 * 1024))
    }
}
