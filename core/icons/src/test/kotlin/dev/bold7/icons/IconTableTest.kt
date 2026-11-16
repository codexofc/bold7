package dev.bold7.icons

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val THEME = "bbos7-9900/theme/net_rim_theme_BlackBerry7_640x480_b"

class IconTableTest {
    private val table = parseIconTable(
        """
        # package	activity	icon	source
        com.google.android.dialer	*	bbos7_phone	$THEME/sys.phone/native/app_icon_99x99.png	A x

        com.example.suite	*	bbos7_suite	icons/apps/suite.png	A x
        com.example.suite	com.example.suite.Mail	bbos7_hub	$THEME/sys.uri.bbhub/uri/images/icon_9.png	A x
        com.example.only	com.example.only.Main	bbos7_clock	icons/apps/clock.png	A x
        """.trimIndent(),
    )

    @Test fun aPackageRowCoversEveryActivityOfThePackage() {
        assertEquals("bbos7_phone", table.iconFor("com.google.android.dialer", "com.google.android.dialer.extensions.GoogleDialtactsActivity"))
        assertEquals("bbos7_phone", table.iconFor("com.google.android.dialer", "com.google.android.dialer.Other"))
    }

    @Test fun anActivityRowWinsOverItsPackageRow() {
        assertEquals("bbos7_hub", table.iconFor("com.example.suite", "com.example.suite.Mail"))
        assertEquals("bbos7_suite", table.iconFor("com.example.suite", "com.example.suite.Drive"))
    }

    // A notification names its package only: the package row, never an activity's.
    @Test fun aPackageAloneTakesItsPackageRowOnly() {
        assertEquals("bbos7_phone", table.iconForPackage("com.google.android.dialer"))
        assertEquals("bbos7_suite", table.iconForPackage("com.example.suite"))
        assertNull(table.iconForPackage("com.example.only"))
        assertNull(table.iconForPackage("org.fdroid.fdroid"))
    }

    @Test fun withoutARowTheAppKeepsItsOwnIcon() {
        assertNull(table.iconFor("org.fdroid.fdroid", "org.fdroid.fdroid.views.main.MainActivity"))
        // An activity row says nothing of the package's other activities.
        assertEquals("bbos7_clock", table.iconFor("com.example.only", "com.example.only.Main"))
        assertNull(table.iconFor("com.example.only", "com.example.only.Settings"))
    }

    @Test fun listsEachIconOnceWithItsSource() {
        assertEquals(
            mapOf(
                "bbos7_phone" to "$THEME/sys.phone/native/app_icon_99x99.png",
                "bbos7_suite" to "icons/apps/suite.png",
                "bbos7_hub" to "$THEME/sys.uri.bbhub/uri/images/icon_9.png",
                "bbos7_clock" to "icons/apps/clock.png",
            ),
            table.sources,
        )
    }

    @Test fun twoAppsMayShareAnIcon() {
        val shared = parseIconTable("a.b\t*\tbbos7_browser\ticons/apps/browser.png\tA x\nc.d\t*\tbbos7_browser\ticons/apps/browser.png\tA x")
        assertEquals("bbos7_browser", shared.iconFor("c.d", "c.d.Main"))
    }

    @Test fun refusesARowWithoutItsFiveColumns() = refuses("line 1", "a.b\t*\tbbos7_x\ticons/apps/x.png")

    @Test fun refusesANameAndroidCannotUseForAResource() {
        refuses("line 2", "a.b\t*\tbbos7_ok\ticons/apps/ok.png\tA x\nc.d\t*\tbbos7-phone\ticons/apps/phone.png\tA x")
        refuses("line 1", "a.b\t*\tPhone\ticons/apps/phone.png\tA x")
        refuses("line 1", "a.b\t*\t1phone\ticons/apps/phone.png\tA x")
    }

    @Test fun refusesASourceThatIsNotAPngInsideAssetsPrivate() {
        refuses("line 1", "a.b\t*\tbbos7_x\ticons/apps/x.jpg\tA x")
        refuses("line 1", "a.b\t*\tbbos7_x\t../keys/x.png\tA x")
        refuses("line 1", "a.b\t*\tbbos7_x\t/Users/x/x.png\tA x")
    }

    @Test fun refusesAProvenanceThatIsNotAKnownSourceKind() {
        refuses("line 1", "a.b\t*\tbbos7_x\ticons/apps/x.png\tfound on a forum")
        refuses("line 1", "a.b\t*\tbbos7_x\ticons/apps/x.png\t")
    }

    @Test fun refusesAnEmptyPackageOrActivity() {
        refuses("line 1", "\t*\tbbos7_x\ticons/apps/x.png\tA x")
        refuses("line 1", "a.b\t\tbbos7_x\ticons/apps/x.png\tA x")
    }

    @Test fun anUnreadableTableCostsTheBb10IconsNotTheHome() {
        val errors = mutableListOf<IllegalArgumentException>()
        val none = parseIconTableOrEmpty("com.google.android.dialer\t*\tbbos7-phone\ticons/apps/phone.png\tA x", errors::add)
        assertNull(none.iconFor("com.google.android.dialer", "com.google.android.dialer.Main"))
        assertEquals(emptyMap(), none.sources)
        assertEquals(1, errors.size)
        assertTrue(errors.single().message!!.startsWith("line 1"))
    }

    @Test fun aGoodTableIsReadAsIs() {
        val errors = mutableListOf<IllegalArgumentException>()
        val table = parseIconTableOrEmpty("a.b\t*\tbbos7_x\ticons/apps/x.png\tA x", errors::add)
        assertEquals("bbos7_x", table.iconFor("a.b", "a.b.Main"))
        assertEquals(emptyList(), errors)
    }

    @Test fun downscalesByHalvesWhileAboveTwiceTheTarget() {
        assertEquals(listOf(90), downscaleSteps(99, 90))
        assertEquals(listOf(90), downscaleSteps(156, 90))
        assertEquals(listOf(90), downscaleSteps(180, 90))
        assertEquals(listOf(107, 90), downscaleSteps(215, 90))
        assertEquals(listOf(200, 100, 90), downscaleSteps(400, 90))
        assertEquals(listOf(90), downscaleSteps(90, 90))
    }

    @Test fun refusesTheSameAppTwice() = refuses("line 2", "a.b\t*\tbbos7_x\ticons/apps/x.png\tA x\na.b\t*\tbbos7_y\ticons/apps/y.png\tA x")

    @Test fun refusesAnIconCopiedFromTwoSources() =
        refuses("line 2", "a.b\t*\tbbos7_x\ticons/apps/x.png\tA x\nc.d\t*\tbbos7_x\ticons/apps/other.png\tA x")

    private fun refuses(where: String, text: String) {
        val e = assertFailsWith<IllegalArgumentException> { parseIconTable(text) }
        assertTrue(e.message!!.startsWith(where), e.message)
    }
}

/**
 * The table the launcher and SystemUI's module ship; each checks that it has every icon the
 * table names (ShippedIconTableTest of the launcher, PreviewTest of system/sysui).
 */
class ShippedIconTableTest {
    private val table = parseIconTable(File("src/main/res/raw/bbos7_icons.tsv").readText())

    /** The 9900's firmware, nothing else: every icon is a PNG of bbos7-9900/. */
    @Test fun everyIconComesFromTheFirmware() {
        assertEquals(emptyMap(), table.sources.filterValues { !it.startsWith("bbos7-9900/") })
    }

    @Test fun everySourceFileExists() {
        val assets = File("../../assets-private")
        org.junit.Assume.assumeTrue("assets-private/bbos7-9900 absent", File(assets, "bbos7-9900").isDirectory)
        assertEquals(emptyList(), table.sources.values.filterNot { File(assets, it).isFile })
    }

    @Test fun theCalendarIconIsInTheTable() = assertTrue(CALENDAR_ICON in table.sources)

    // Text Messages is an activity of Bold7's Messages; Google Messages takes
    // the generated icon, so that Show All never shows two Text Messages icons.
    @Test fun textMessagesIsAnIconOfBold7sMessages() {
        assertEquals("bbos7_messages", table.iconFor("dev.bold7.messages", "dev.bold7.messages.MessagesActivity"))
        assertEquals("bbos7_text_messages", table.iconFor("dev.bold7.messages", "dev.bold7.messages.TextMessages"))
        assertNull(table.iconForPackage("com.google.android.apps.messaging"))
    }
}
