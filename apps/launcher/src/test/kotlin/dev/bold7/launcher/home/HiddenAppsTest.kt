package dev.bold7.launcher.home

import dev.bold7.icons.parseAppTable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenAppsTest {
    private val table = parseAppTable("org.fdroid.fdroid\t*\t-\thidden\tD1")
    private val fdroid = App("org.fdroid.fdroid", "org.fdroid.fdroid.Main", "F-Droid")
    private val maps = App("maps", "maps.Main", "Maps")

    @Test fun withoutAChoiceTheTableDecides() {
        val hiding = Hiding(table)
        assertTrue(hiding.hides(fdroid))
        assertFalse(hiding.hides(maps))
    }

    @Test fun myChoiceWinsOverTheTableBothWays() {
        val hiding = Hiding(table, mapOf("org.fdroid.fdroid/org.fdroid.fdroid.Main" to false, "maps/maps.Main" to true))
        assertFalse(hiding.hides(fdroid))
        assertTrue(hiding.hides(maps))
    }

    // A choice that says what the table says is dropped: a later table change still applies.
    @Test fun aChoiceIsKeptOnlyWhereItDiffersFromTheTable() {
        val shown = Hiding(table).with(fdroid, hidden = false)
        assertEquals(mapOf("org.fdroid.fdroid/org.fdroid.fdroid.Main" to false), shown.choices)
        assertEquals(emptyMap(), shown.with(fdroid, hidden = true).choices)
        assertEquals(emptyMap(), Hiding(table).with(maps, hidden = false).choices)
    }

    @Test fun choicesAreWrittenOneLinePerApp() {
        val choices = mapOf("maps/maps.Main" to true, "org.fdroid.fdroid/org.fdroid.fdroid.Main" to false)
        assertEquals("maps/maps.Main\thidden\norg.fdroid.fdroid/org.fdroid.fdroid.Main\tshown\n", encodeChoices(choices))
        assertEquals(choices, decodeChoices(encodeChoices(choices)))
    }

    @Test fun aLineThatCannotBeReadIsSkipped() =
        assertEquals(mapOf("maps/maps.Main" to true), decodeChoices("maps/maps.Main\thidden\nmaps\thidden\nzoom/zoom.Main\tgone\n\n"))

    @Test fun theFileKeepsTheChoicesAcrossARestart() {
        val file = File(Files.createTempDirectory("hidden").toFile(), "hidden-apps.tsv")
        assertEquals(emptyMap(), HiddenAppsFile { file }.read())
        HiddenAppsFile { file }.write(mapOf("maps/maps.Main" to true))
        assertEquals(mapOf("maps/maps.Main" to true), HiddenAppsFile { file }.read())
    }
}
