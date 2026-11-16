package dev.bold7.messages

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.w3c.dom.Element

class ConsentProviderTest {
    @Test fun answersTheConsentAndNothingElse() {
        assertEquals(true, consentAnswer("consent") { true })
        assertEquals(false, consentAnswer("consent") { false })
        assertNull(consentAnswer("consent") { null })
        assertNull(consentAnswer("query") { error("read for another method") })
    }

    /** Exported behind its signature permission only, which the launcher asks and nobody else of Bold7. */
    @Test fun theProviderIsBehindItsSignaturePermission() {
        val android = "http://schemas.android.com/apk/res/android"
        fun elements(path: String, tag: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path)).getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
        val provider = elements("src/main/AndroidManifest.xml", "provider").single { it.getAttributeNS(android, "name") == ".ConsentProvider" }
        assertEquals(READ_TEXT_CONSENT, provider.getAttributeNS(android, "permission"))
        assertEquals("", provider.getAttributeNS(android, "readPermission"))
        assertEquals("", provider.getAttributeNS(android, "grantUriPermissions"))
        for (manifest in listOf("src/main/AndroidManifest.xml", "../launcher/src/main/AndroidManifest.xml")) {
            val permission = elements(manifest, "permission").single { it.getAttributeNS(android, "name") == READ_TEXT_CONSENT }
            assertEquals("signature", permission.getAttributeNS(android, "protectionLevel"), manifest)
        }
        val askers = File("..").listFiles()!!.filter { File(it, "src/main/AndroidManifest.xml").exists() }
            .filter { app -> elements("$app/src/main/AndroidManifest.xml", "uses-permission").any { it.getAttributeNS(android, "name") == READ_TEXT_CONSENT } }
        assertEquals(listOf("launcher"), askers.map { it.name })
    }
}
