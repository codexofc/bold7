package dev.bold7.launcher.home

import dev.bold7.icons.TEMPLATE_MASK
import dev.bold7.icons.TEMPLATE_OVERLAY
import dev.bold7.icons.TEMPLATE_UNDERLAY
import dev.bold7.icons.parseIconTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The images the launcher reads by name (core/theme), as tools/import-assets.sh writes them into
 * assets-gen: the table of core/icons and its template, and every theme image its sources name.
 * The development build adds them to the APK and is not built unless this passes, so the phone
 * never shows an empty image (apps/launcher/build.gradle.kts). Skipped without assets-gen (a
 * public build on a machine without the firmware: the APK carries none of them).
 */
class ShippedIconTableTest {
    private val table = parseIconTable(File("../../core/icons/src/main/res/raw/bbos7_icons.tsv").readText())
    private val gen = File("../../assets-gen")

    private fun image(name: String) = listOf("launcher", "ui").any { block ->
        listOf(".png", ".9.png").any { File(gen, "$block/res/drawable-nodpi/$name$it").exists() }
    }

    @Test fun everyIconHasItsDrawable() {
        assumeTrue(File(gen, "launcher/res").isDirectory)
        val missing = (table.sources.keys + listOf(TEMPLATE_UNDERLAY, TEMPLATE_MASK, TEMPLATE_OVERLAY)).filterNot { File(gen, "launcher/res/drawable-nodpi/$it.png").exists() }
        assertEquals(emptyList(), missing, "run tools/import-assets.sh")
    }

    /** No R field checks a name any more: a misspelt one would draw nothing. */
    @Test fun everyThemeNameHasItsImage() {
        assumeTrue(File(gen, "launcher/res").isDirectory)
        val names = File("src/main/kotlin").walk().filter { it.extension == "kt" }
            .flatMap { THEME_NAME.findAll(it.readText()).map { m -> m.groupValues[1] } }.toSortedSet()
        assertTrue(names.size > 80, "names found: ${names.size}")
        assertEquals(emptyList(), names.filterNot(::image), "run tools/import-assets.sh, or a name is wrong")
    }

    private companion object {
        // The launcher's own blocks (accueil, options, profils, connexions) and core/ui's.
        val THEME_NAME = Regex("\"((?:home|opt|profile|connections|ui)_[a-z0-9_]+)\"")
    }
}
