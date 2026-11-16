package dev.bold7.icons

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppTableTest {
    private val table = parseAppTable(
        """
        # package	activity	name	shown	source
        com.android.chrome	*	Browser	shown	A sys.browser bar-descriptor.xml

        com.google.android.apps.messaging	*	Text Messages	hidden	A sys.chat bar-descriptor.xml
        org.fdroid.fdroid	*	-	hidden	D1
        com.example.suite	*	Suite	shown	C capture
        com.example.suite	com.example.suite.Admin	-	hidden	D1
        """.trimIndent(),
    )

    @Test fun aPackageRowNamesEveryActivityOfThePackage() {
        assertEquals("Browser", table.nameFor("com.android.chrome", "com.google.android.apps.chrome.Main"))
        assertEquals("Browser", table.nameForPackage("com.android.chrome"))
    }

    @Test fun withoutARowOrANameTheAppKeepsAndroidsName() {
        assertNull(table.nameFor("com.google.android.dialer", "com.google.android.dialer.Main"))
        assertNull(table.nameFor("org.fdroid.fdroid", "org.fdroid.fdroid.views.main.MainActivity"))
        assertNull(table.nameForPackage("org.fdroid.fdroid"))
    }

    @Test fun anAppIsShownUnlessItsRowHidesIt() {
        assertFalse(table.hidden("com.android.chrome", "com.google.android.apps.chrome.Main"))
        assertTrue(table.hidden("org.fdroid.fdroid", "org.fdroid.fdroid.views.main.MainActivity"))
        // A new app, unknown to the table, shows.
        assertFalse(table.hidden("org.signal", "org.signal.Main"))
    }

    // Google Messages: hidden from the grid, still "Text Messages" in the search and its frame.
    @Test fun aHiddenAppKeepsItsName() {
        assertTrue(table.hidden("com.google.android.apps.messaging", "com.google.android.apps.messaging.ui.ConversationListActivity"))
        assertEquals("Text Messages", table.nameFor("com.google.android.apps.messaging", "com.google.android.apps.messaging.ui.ConversationListActivity"))
    }

    // As for the icons: the activity's own row, whole, over its package's.
    @Test fun anActivityRowWinsOverItsPackageRow() {
        assertTrue(table.hidden("com.example.suite", "com.example.suite.Admin"))
        assertNull(table.nameFor("com.example.suite", "com.example.suite.Admin"))
        assertFalse(table.hidden("com.example.suite", "com.example.suite.Main"))
        assertEquals("Suite", table.nameFor("com.example.suite", "com.example.suite.Main"))
        assertEquals("Suite", table.nameForPackage("com.example.suite"))
    }

    @Test fun listsTheRowsWithTheirSource() {
        assertEquals("A sys.chat bar-descriptor.xml", table.rows.getValue("com.google.android.apps.messaging" to null).source)
        assertEquals(5, table.rows.size)
    }

    @Test fun refusesARowWithoutItsFiveColumns() = refuses("line 1", "a.b\t*\tName\tshown")

    @Test fun refusesAStateOtherThanShownOrHidden() = refuses("line 1", "a.b\t*\tName\tvisible\tA x")

    @Test fun refusesAnEmptyField() {
        refuses("line 1", "\t*\tName\tshown\tA x")
        refuses("line 1", "a.b\t\tName\tshown\tA x")
        refuses("line 1", "a.b\t*\t\tshown\tA x")
        refuses("line 1", "a.b\t*\tName\tshown\t")
    }

    // Shown under Android's name: a row that changes nothing.
    @Test fun refusesARowThatChangesNothing() = refuses("line 1", "a.b\t*\t-\tshown\tA x")

    @Test fun refusesTheSameAppTwice() = refuses("line 2", "a.b\t*\tA\tshown\tA x\na.b\t*\t-\thidden\tD1")

    @Test fun anUnreadableTableShowsEveryAppUnderItsOwnName() {
        val errors = mutableListOf<IllegalArgumentException>()
        val none = parseAppTableOrEmpty("org.fdroid.fdroid\t*\t-\tgone\tD1", errors::add)
        assertFalse(none.hidden("org.fdroid.fdroid", "org.fdroid.fdroid.Main"))
        assertEquals(1, errors.size)
        assertTrue(errors.single().message!!.startsWith("line 1"))
    }

    private fun refuses(where: String, text: String) {
        val e = assertFailsWith<IllegalArgumentException> { parseAppTable(text) }
        assertTrue(e.message!!.startsWith(where), e.message)
    }
}

/** The table the launcher ships (docs/BBOS7-VISUEL.md). */
class ShippedAppTableTest {
    private val table = parseAppTable(File("src/main/res/raw/bbos7_apps.tsv").readText())

    /**
     * Every name has its source: A firmware, B manual, C capture or D deduced, and a deduced one
     * says why. The manual's names are checked against its text when it is here (assets-private/).
     */
    @Test fun everyNameIsSourced() {
        val unsourced = table.rows.filter { (_, row) -> row.source.length < 3 || row.source[0] !in "ABCD" || row.source[1] != ' ' }
        assertEquals(emptyMap(), unsourced)
        val manual = File("../../assets-private/reference-bbos7/manuels/UserGuide_7.1_US.txt")
        if (!manual.isFile) return
        val text = manual.readText()
        for (row in table.rows.values) {
            if (row.name == null || !row.source.startsWith("B ")) continue
            assertTrue(row.name in text, row.name)
        }
    }

    @Test fun messagesAndTextMessagesAreBold7sOwnAndGoogleMessagesHidesBehindThem() {
        // Bold7's inbox is Messages; its texts are Text Messages,
        // a second icon of the same app (sim-03, C); Google Messages, the engine of the texts, is
        // hidden, named Messages in the switcher, never a second Text Messages; Gmail keeps its own name, a
        // mail account's (gsm 020).
        assertFalse(table.hidden("dev.bold7.messages", "dev.bold7.messages.MessagesActivity"))
        assertEquals("Messages", table.nameFor("dev.bold7.messages", "dev.bold7.messages.MessagesActivity"))
        assertEquals("Messages", table.nameForPackage("dev.bold7.messages"))
        assertFalse(table.hidden("dev.bold7.messages", "dev.bold7.messages.TextMessages"))
        assertEquals("Text Messages", table.nameFor("dev.bold7.messages", "dev.bold7.messages.TextMessages"))
        assertTrue(table.hidden("com.google.android.apps.messaging", "com.google.android.apps.messaging.ui.ConversationListActivity"))
        assertEquals("Messages", table.nameForPackage("com.google.android.apps.messaging")) // sim-05 names apps the BB way
        assertEquals(null, table.nameForPackage("com.google.android.gm"))
        assertFalse(table.hidden("com.google.android.gm", "com.google.android.gm.ConversationListActivityGmail"))
    }

    @Test fun phoneIsBold7sOwnAndGooglesPhoneHidesBehindIt() {
        // Bold7's Phone is Phone; Google's, the system dialer and the way back, is hidden.
        assertFalse(table.hidden("dev.bold7.phone", "dev.bold7.phone.PhoneActivity"))
        assertEquals("Phone", table.nameForPackage("dev.bold7.phone"))
        assertTrue(table.hidden("com.google.android.dialer", "com.google.android.dialer.extensions.GoogleDialtactsActivity"))
        assertEquals(null, table.nameForPackage("com.google.android.dialer"))
    }

    @Test fun optionsIsBold7sOwnAndAndroidsSettingsHidesBehindIt() {
        // the launcher's OptionsActivity is Options; Android's Settings, which the entries
        // Options does not draw open, is hidden. The launcher's other activities have no row.
        assertFalse(table.hidden("dev.bold7.launcher", "dev.bold7.launcher.options.OptionsActivity"))
        assertEquals("Options", table.nameFor("dev.bold7.launcher", "dev.bold7.launcher.options.OptionsActivity"))
        assertTrue(table.hidden("com.android.settings", "com.android.settings.Settings"))
        assertEquals(null, table.nameForPackage("com.android.settings"))
    }

    @Test fun clockIsBold7sOwnAndGooglesClockHidesBehindIt() {
        // Bold7's Clock is Clock; Google's keeps its alarms, out of the grid.
        assertFalse(table.hidden("dev.bold7.clock", "dev.bold7.clock.ClockActivity"))
        assertEquals("Clock", table.nameForPackage("dev.bold7.clock"))
        assertTrue(table.hidden("com.google.android.deskclock", "com.android.deskclock.DeskClock"))
        assertEquals(null, table.nameForPackage("com.google.android.deskclock"))
    }

    @Test fun calendarIsBold7sOwnAndGoogleCalendarHidesBehindIt() {
        // Bold7's Calendar is Calendar; Google's keeps ringing the reminders, out of the grid.
        assertFalse(table.hidden("dev.bold7.calendar", "dev.bold7.calendar.CalendarActivity"))
        assertEquals("Calendar", table.nameForPackage("dev.bold7.calendar"))
        assertTrue(table.hidden("com.google.android.calendar", "com.android.calendar.AllInOneActivity"))
        assertEquals(null, table.nameForPackage("com.google.android.calendar"))
    }

    @Test fun contactsIsBold7sOwnAndGooglesContactsHidesBehindIt() {
        // Bold7's Contacts is Contacts; Google's, over the same provider, is hidden.
        assertFalse(table.hidden("dev.bold7.contacts", "dev.bold7.contacts.ContactsActivity"))
        assertEquals("Contacts", table.nameForPackage("dev.bold7.contacts"))
        assertTrue(table.hidden("com.google.android.contacts", "com.android.contacts.activities.PeopleActivity"))
        assertEquals(null, table.nameForPackage("com.google.android.contacts"))
    }

    @Test fun calculatorIsBold7sOwnAndGooglesCalculatorHidesBehindIt() {
        // Bold7's Calculator is Calculator; Google's leaves the grid.
        assertFalse(table.hidden("dev.bold7.calculator", "dev.bold7.calculator.CalcActivity"))
        assertEquals("Calculator", table.nameForPackage("dev.bold7.calculator"))
        assertTrue(table.hidden("com.google.android.calculator", "com.android.calculator2.Calculator"))
        assertEquals(null, table.nameForPackage("com.google.android.calculator"))
    }

    @Test fun flashlightIsBold7sOwnAndShownUnderItsName() {
        // new on the grid, its icon is the generated tile (no row in the icon table).
        assertFalse(table.hidden("dev.bold7.flashlight", "dev.bold7.flashlight.FlashActivity"))
        assertEquals("Flashlight", table.nameForPackage("dev.bold7.flashlight"))
    }

    @Test fun memopadIsBold7sOwnAndGoogleKeepHidesBehindIt() {
        // Bold7's MemoPad is MemoPad; Google Keep, its notes untouched, leaves the grid.
        assertFalse(table.hidden("dev.bold7.memopad", "dev.bold7.memopad.MemoListActivity"))
        assertEquals("MemoPad", table.nameForPackage("dev.bold7.memopad"))
        assertTrue(table.hidden("com.google.android.keep", "com.google.android.keep.activities.BrowseActivity"))
        assertEquals(null, table.nameForPackage("com.google.android.keep"))
    }

    @Test fun picturesIsBold7sOwnAndAvesHidesBehindIt() {
        // Bold7's Pictures is Pictures; Aves, over the same MediaStore, leaves the grid.
        assertFalse(table.hidden("dev.bold7.pictures", "dev.bold7.pictures.PicturesActivity"))
        assertEquals("Pictures", table.nameForPackage("dev.bold7.pictures"))
        assertTrue(table.hidden("deckers.thibault.aves.libre", "deckers.thibault.aves.MainActivity"))
        assertEquals(null, table.nameForPackage("deckers.thibault.aves.libre"))
    }

    @Test fun tasksIsBold7sOwnAndShownUnderItsName() {
        // nothing on the Q25 to hide behind it.
        assertFalse(table.hidden("dev.bold7.tasks", "dev.bold7.tasks.TaskListActivity"))
        assertEquals("Tasks", table.nameForPackage("dev.bold7.tasks"))
    }

    @Test fun googleAndItsAssistantAreHidden() {
        assertTrue(table.hidden("com.google.android.googlequicksearchbox", "x"))
        assertTrue(table.hidden("com.google.android.apps.googleassistant", "x"))
    }

    @Test fun thePlayStoreIsBlackBerryAppWorld() =
        assertEquals("BlackBerry App World", table.nameFor("com.android.vending", "com.android.vending.AssetBrowserActivity"))

    // The 40 launcher activities of the Q25 (`cmd package query-activities`, minus Google TV and YouTube Music, uninstalled), 27
    // since Google Messages, Google's Phone, Clock and Contacts, Google Calendar and Android's Settings
    // hide behind Bold7's Messages, Phone, Clock, Contacts, Calendar and Options
    // Google's Calculator behind Bold7's, Google Keep behind Bold7's MemoPad and Aves behind
    // Bold7's Pictures, which are not among them.
    @Test fun leavesTwelveAppsOfTheQ25OnTheGrid() {

        val q25 = """
            com.android.chrome/com.google.android.apps.chrome.Main com.android.fmradio/.FmMainActivity
            com.android.settings/.Settings com.android.soundrecorder/.SoundRecorder com.android.stk/.StkMain
            com.android.vending/.AssetBrowserActivity com.duc1607.q25led/.MainActivity
            com.duc1607.resolutionchanger/.MainActivity com.google.android.apps.adm/.activities.MainActivity
            com.google.android.apps.docs/.app.NewMainProxyActivity com.google.android.apps.googleassistant/.AssistantActivity
            com.google.android.apps.maps/com.google.android.maps.MapsActivity
            com.google.android.apps.messaging/.ui.ConversationListActivity com.google.android.apps.photos/.home.HomeActivity
            com.google.android.apps.safetyhub/.LauncherActivity com.google.android.apps.tachyon/.MainActivity
            com.google.android.apps.walletnfcrel/com.google.commerce.tapandpay.android.wallet.WalletActivity
            com.google.android.calculator/com.android.calculator2.Calculator
            com.google.android.calendar/com.android.calendar.AllInOneActivity
            com.google.android.contacts/com.android.contacts.activities.PeopleActivity
            com.google.android.deskclock/com.android.deskclock.DeskClock
            com.google.android.dialer/.extensions.GoogleDialtactsActivity com.google.android.gm/.ConversationListActivityGmail
            com.google.android.googlequicksearchbox/.SearchActivity com.google.android.keep/.activities.BrowseActivity
            com.google.android.play.games/com.google.android.gms.games.ui.destination.main.MainActivity
            com.google.android.youtube/.app.honeycomb.Shell${'$'}HomeActivity
            com.light.lightcamera/.MainActivity com.machiav3lli.backup/.ui.activities.NeoActivity
            com.pranavpandey.rotation/.activity.SplashActivity de.monocles.browser/.activities.MainWebViewActivity
            deckers.thibault.aves.libre/deckers.thibault.aves.MainActivity io.github.sds100.keymapper/.MainActivity
            kwlkkw.odbaum.pebxnq/.ui.MainActivity org.fdroid.fdroid/.views.main.MainActivity
            org.fossify.filemanager/.activities.SplashActivity.Green
            ph.dgsd.Custota/com.chiller3.custota.settings.SettingsActivity tech.shroyer.q25trackpadcustomizer/.MainActivity
            vctsrt.cntgtj.uqfwgg/.ui.MainActivity xyz.cybertopia.salam/eu.siacs.conversations.ui.ConversationActivity
        """.split(Regex("\\s+")).filter(String::isNotEmpty).map { c ->
            val (pkg, cls) = c.split('/')
            pkg to if (cls.startsWith(".")) pkg + cls else cls
        }
        assertEquals(40, q25.size)
        val shown = q25.filterNot { (pkg, cls) -> table.hidden(pkg, cls) }.map { (pkg, cls) -> table.nameFor(pkg, cls) ?: pkg }
        assertEquals(
            listOf(
                "Browser", "com.android.fmradio", "Voice Notes Recorder", "BlackBerry App World",
                "com.google.android.apps.docs", "Maps", "com.google.android.apps.tachyon", "Wallet",
                "com.google.android.gm",
                "com.google.android.youtube", "Camera", "Files",
            ),
            shown,
        )
    }
}
