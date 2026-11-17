package dev.bold7.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserIconsTest {
    // The firmware's OS 7 icon first, then the user's own file, then the generated one.
    @Test fun theFirmwareWinsThenTheUsersFileThenTheGeneratedIcon() {
        assertEquals(IconSource.TABLE, iconSource(inTable = true, userIcon = true))
        assertEquals(IconSource.TABLE, iconSource(inTable = true, userIcon = false))
        assertEquals(IconSource.USER, iconSource(inTable = false, userIcon = true))
        assertEquals(IconSource.GENERATED, iconSource(inTable = false, userIcon = false))
    }

    @Test fun aFileNamesItsAppByPackage() {
        assertEquals("com.whatsapp", userIconPackage("com.whatsapp.png"))
        assertEquals("com.instagram.android", userIconPackage("com.instagram.android.png"))
        assertEquals("org.lineage_os.app2", userIconPackage("org.lineage_os.app2.png"))
    }

    @Test fun anythingElseInTheFolderIsIgnored() {
        for (name in listOf("whatsapp.png", "com.whatsapp.jpg", "com.whatsapp.PNG", "com.whatsapp", ".png", "com..whatsapp.png",
            "com.2whatsapp.png", "../com.whatsapp.png", "com.whatsapp .png", "com.whatsapp.png.png.txt")) {
            assertNull(userIconPackage(name), name)
        }
    }

    @Test fun theListingNamesEachPackageOnce() {
        val listing = userIconListing(listOf(
            UserIconFile("com.whatsapp.png", 10, 1), UserIconFile("notes.txt", 3, 1), UserIconFile("com.instagram.android.png", 20, 2),
        ))
        assertEquals(setOf("com.whatsapp", "com.instagram.android"), listing.keys)
        assertEquals(Stamp(20, 2), listing["com.instagram.android"])
    }

    // A file added, replaced (its size or date) or removed redraws its app; nothing else does.
    @Test fun onlyTheAppsWhoseFileChangedAreRedrawn() {
        val before = mapOf("com.whatsapp" to Stamp(10, 1), "com.instagram.android" to Stamp(20, 2), "org.example.kept" to Stamp(5, 5))
        val after = mapOf("com.whatsapp" to Stamp(10, 3), "org.example.kept" to Stamp(5, 5), "org.example.new" to Stamp(1, 1))
        assertEquals(setOf("com.whatsapp", "com.instagram.android", "org.example.new"), changedPackages(before, after))
        assertTrue(changedPackages(after, after).isEmpty())
    }

    // A 92x92 image is already an icon of the 9900's grid: shown as it is; any other size goes
    // through the same OS 7 rendering as a generated icon.
    @Test fun onlyAnImageOfTheGridsSizeIsShownAsItIs() {
        assertTrue(shownAsIs(92, 92))
        assertFalse(shownAsIs(96, 96))
        assertFalse(shownAsIs(92, 90))
        assertFalse(shownAsIs(512, 512))
    }

    // A large image is decoded smaller, never under twice the icon's size.
    @Test fun aLargeImageIsDecodedAtAPowerOfTwo() {
        assertEquals(1, sampleSize(92, 92))
        assertEquals(1, sampleSize(300, 300))
        assertEquals(2, sampleSize(400, 400))
        assertEquals(4, sampleSize(1024, 900))
        assertEquals(1, sampleSize(0, 0))
    }
}
