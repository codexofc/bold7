package dev.bold7.firmware

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Layer.lay] on a made-up gen/, and [candidates] on files of made-up sizes. */
class LayerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stock = """
        <?xml version="1.0" encoding="utf-8"?>
        <familyset>
            <family name="sans-serif">
                <font weight="400" style="normal">Roboto-Regular.ttf</font>
            </family>
            <family name="sans-serif-condensed">
                <font weight="400" style="normal">RobotoCondensed-Regular.ttf</font>
            </family>
            <family name="serif">
                <font weight="400" style="normal">NotoSerif-Regular.ttf</font>
            </family>
            <family name="monospace">
                <font weight="400" style="normal">DroidSansMono.ttf</font>
            </family>
        </familyset>
    """.trimIndent() + "\n"

    private fun file(dir: File, rel: String, text: String = rel) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text) }

    private fun gen(name: String = "gen"): File {
        val g = tmp.newFolder(name)
        file(g, "ui/res/drawable-nodpi/ui_menu.9.png")
        file(g, "launcher/res/drawable-nodpi/home_banner.png")
        file(g, "sounds/net_rim_x/a.m4a")
        file(g, "wallpapers/1.png")
        file(g, "module/product/media/audio/ringtones/Spirit.m4a")
        file(g, "module/product/media/audio/ui/LowBattery.ogg")
        FontsXml.BBALPHA.values.flatten().forEach { file(g, "module/system/fonts/${it.second}") }
        FontsXml.FALLBACKS.flatMap { it.second }.forEach { file(g, "module/system/fonts-os7/${it.second}") }
        return g
    }

    private fun tree(dir: File) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toSortedSet()

    @Test
    fun laysEveryPartWhereTheModuleMountsIt() {
        val layer = tmp.newFolder("layer")
        file(layer, "product/usr/keylayout/Q25_keyboard.kl") // already in the zip: kept
        assertEquals("2 images, 2 sounds, 23 fonts", Layer.lay(gen(), stock, layer))
        val fonts = FontsXml.FILES.map { "system/fonts/$it" }
        assertEquals(
            (fonts + listOf(
                "product/media/bold7/launcher/res/drawable-nodpi/home_banner.png",
                "product/media/bold7/ui/res/drawable-nodpi/ui_menu.9.png",
                "product/media/bold7/wallpapers/1.png",
                "product/media/audio/ringtones/Spirit.m4a",
                "product/media/audio/ui/LowBattery.ogg",
                "product/media/bootanimation.zip",
                "product/usr/keylayout/Q25_keyboard.kl",
                "system/etc/fonts.xml",
            )).toSortedSet(),
            tree(layer),
        )
        assertEquals("module/product/media/audio/ringtones/Spirit.m4a", File(layer, "product/media/audio/ringtones/Spirit.m4a").readText())
        assertTrue(File(layer, "system/etc/fonts.xml").readText().contains("<font weight=\"400\" style=\"normal\">BBAlphaSans.ttf</font>"))
        ZipFile(File(layer, "product/media/bootanimation.zip")).use { assertEquals(168, it.size()) } // desc, 164 + 1 frames, 2 trims
    }

    @Test
    fun refusesAnExtractionWithoutItsFonts() {
        val g = gen()
        File(g, "module/system/fonts-os7/BBThai.ttf").delete()
        val e = assertFailsWith<IOException> { Layer.lay(g, stock, tmp.newFolder("layer")) }
        assertTrue("BBThai.ttf" in e.message!!)
    }

    @Test
    fun refusesAFontsXmlAlreadyDerived() {
        val layer = tmp.newFolder("layer")
        Layer.lay(gen(), stock, layer)
        assertFailsWith<IOException> { Layer.lay(gen("gen2"), File(layer, "system/etc/fonts.xml").readText(), tmp.newFolder("layer2")) }
    }

    @Test
    fun candidatesAreTheFilesOfAKnownSize() {
        val dir = tmp.newFolder("Download")
        fun sized(name: String, size: Long) = RandomAccessFile(File(dir, name), "rw").use { it.setLength(size) }
        sized("b.exe", Firmware.LOADER.size)
        sized("a.bin", Firmware.SIMULATOR.size)
        sized("c.exe", Firmware.LOADER.size + 1)
        File(dir, "d").mkdir()
        assertEquals(listOf("a.bin", "b.exe"), candidates(dir).map { it.name })
        sized(Firmware.SIMULATOR.fileName, 1000)
        assertEquals(listOf(Firmware.SIMULATOR.fileName), misnamed(dir).map { it.name })
        assertEquals(emptyList(), candidates(File(dir, "d")))
    }
}
