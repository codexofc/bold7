package dev.bold7.design

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bold7 speaks OS 7's British English whatever the phone's language: a screen that
 * would come out in the phone's language fails here, at its cause.
 */
class Os7LanguageTest {
    private val root = File("../..")

    @Test fun os7SpeaksBritishEnglishWithThe9900sNames() {
        assertEquals("en-GB", OS7_LOCALE.toLanguageTag())
        // CLDR's en-GB writes "Sept" and "pm"; the 9900, "Sep" (gsm 027) and "PM" (sim-03).
        assertEquals("Fri 16 Sep 2011", java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", OS7_NAMES).format(java.time.LocalDate.of(2011, 9, 16)))
        assertEquals("1:05 PM", java.time.format.DateTimeFormatter.ofPattern("h:mm a", OS7_NAMES).format(java.time.LocalTime.of(13, 5)))
    }

    /** Every app with a component runs its process in OS 7's language, set first thing at its start. */
    @Test fun everyAppSpeaksOs7Language() {
        val apps = File(root, "apps").listFiles { f -> File(f, "src/main/AndroidManifest.xml").isFile }!!.sorted()
        assertTrue(apps.size >= 13, "apps found: $apps")
        for (app in apps) {
            val manifest = File(app, "src/main/AndroidManifest.xml").readText()
            if (!Regex("<(activity|service|receiver|provider)\\b").containsMatchIn(manifest)) continue
            val name = Regex("<application[^>]*android:name=\"[.\\w]*?(\\w+)\"").find(manifest)?.groupValues?.get(1)
            assertTrue(name != null, "${app.name}: no Application class to set OS 7's language")
            val source = app.resolve("src/main").walk().firstOrNull { it.name == "$name.kt" }
            assertTrue(source != null, "${app.name}: $name.kt not found")
            val text = source.readText()
            assertTrue(
                Regex("enableStrictModeIfDebuggable\\(\\)\\s*\\n\\s*speakOs7Language\\(\\)").containsMatchIn(text),
                "${app.name}: $name.onCreate() must call speakOs7Language() right after enableStrictModeIfDebuggable()",
            )
        }
    }

    /** A language folder in an app or core/ would never be read any more: a sign of a screen meant for the phone's language. */
    @Test fun noTranslationInTheAppsNorCore() {
        val language = Regex("values-(b\\+)?[a-z]{2,3}(-.*)?")
        val found = listOf("apps", "core").flatMap { dir ->
            File(root, dir).walk().filter { it.isDirectory && it.parentFile.name == "res" && it.path.contains("/src/main/") && language.matches(it.name) }.toList()
        }
        assertEquals(emptyList(), found.map { it.relativeTo(root).path })
    }

    /** SystemUI speaks the phone's language: Bold7's resources are opened there in OS 7's. */
    @Test fun systemUiReadsBold7sResourcesInOs7Language() {
        val module = File(root, "system/sysui/src/main/kotlin/dev/bold7/sysui").walk().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        val opens = Regex("getResourcesForApplication\\(([^)]*)\\)").findAll(module).map { it.groupValues[1] }.toList()
        assertTrue(opens.isNotEmpty())
        opens.forEach { assertEquals("moduleApplicationInfo, os7", it) }
        assertTrue("setLocales(LocaleList(OS7_LOCALE))" in module)
    }
}
