package dev.bold7.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageIndexTest {
    @Test
    fun namesComeFromTheBlocksDrawableFolders() {
        val index = ImageIndex.of(
            listOf(
                "calculator/res/drawable-nodpi/calc_key.9.png",
                "ui/res/drawable-nodpi/ui_menu.9.png",
                "sysui/res/drawable-nodpi/shutdown_busy.gif",
                "launcher/res/drawable-nodpi/home_banner_background.png",
                "sounds/media/audio/ui/Lock.ogg",
                "saisie/assets/dictionary/en.tsv",
                "launcher/res/drawable-nodpi/notes.txt",
            ),
        )
        assertEquals(
            mapOf(
                "calc_key" to "calculator/res/drawable-nodpi/calc_key.9.png",
                "ui_menu" to "ui/res/drawable-nodpi/ui_menu.9.png",
                "shutdown_busy" to "sysui/res/drawable-nodpi/shutdown_busy.gif",
                "home_banner_background" to "launcher/res/drawable-nodpi/home_banner_background.png",
            ),
            index,
        )
    }

    @Test
    fun aNameInTwoBlocksIsTheFirstBlocksInOrder() {
        val index = ImageIndex.of(
            listOf("permissioncontroller/res/drawable-nodpi/bold7_button.9.png", "android/res/drawable-nodpi/bold7_button.9.png"),
        )
        assertEquals("android/res/drawable-nodpi/bold7_button.9.png", index["bold7_button"])
    }

    @Test
    fun onlyResourceNamesAreAsked() {
        assertTrue(ImageIndex.isName("home_banner_background"))
        assertTrue(ImageIndex.isName("ui_sm_full_menu"))
        assertFalse(ImageIndex.isName("../ui/res/drawable-nodpi/ui_menu"))
        assertFalse(ImageIndex.isName("ui/menu"))
        assertFalse(ImageIndex.isName(""))
        assertFalse(ImageIndex.isName("Home"))
    }
}
