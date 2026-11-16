package dev.bold7.launcher.home

import dev.bold7.icons.TEMPLATE_MASK
import dev.bold7.icons.TEMPLATE_OVERLAY
import dev.bold7.icons.TEMPLATE_UNDERLAY
import dev.bold7.icons.parseIconTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The table of core/icons, as the launcher ships it. Its icons are copied by
 * tools/import-assets.sh into assets-gen/launcher/res, which the build adds; the APK is not
 * built unless this passes, so the phone never shows an empty icon (apps/launcher/build.gradle.kts).
 */
class ShippedIconTableTest {
    private val table = parseIconTable(File("../../core/icons/src/main/res/raw/bbos7_icons.tsv").readText())

    @Test fun everyIconHasItsDrawable() {
        val missing = (table.sources.keys + listOf(TEMPLATE_UNDERLAY, TEMPLATE_MASK, TEMPLATE_OVERLAY)).filterNot { File("../../assets-gen/launcher/res/drawable-nodpi/$it.png").exists() }
        assertEquals(emptyList(), missing, "run tools/import-assets.sh")
    }
}
