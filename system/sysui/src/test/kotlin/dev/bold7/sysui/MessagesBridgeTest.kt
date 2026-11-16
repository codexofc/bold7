package dev.bold7.sysui

import java.io.File
import java.security.KeyStore
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessagesBridgeTest {
    private val phenotype = mapOf("bugle_phenotype__enable_andromeda_mde" to false, "bugle_phenotype__other" to 3)

    @Test fun otherPreferencesAreLeftAlone() {
        assertNull(bridgeFlags(mapOf("rcs_msisdn" to "x", "enable_andromeda_mde" to false), "abcd"))
        assertNull(bridgeFlags(emptyMap<String, Any?>(), "abcd"))
    }

    @Test fun opensTheTwoSwitchesAndAddsTheSignature() {
        val out = bridgeFlags(phenotype, "abcd")!!
        assertEquals(true, out["bugle_phenotype__enable_andromeda_mde"])
        assertEquals(true, out["bugle_phenotype__enable_device_always_support_andromeda"])
        assertEquals("$BRIDGE_PACKAGE,abcd", out["bugle_phenotype__external_api_extra_allowed_signatures"])
        assertEquals(3, out["bugle_phenotype__other"])
        // Never the switch that lets any app in.
        assertNull(out["bugle_phenotype__allow_any_app_to_connect_do_not_use_in_public_builds"])
    }

    @Test fun keepsSignaturesGoogleAlreadyAllows() {
        val prefs = phenotype + ("bugle_phenotype__external_api_extra_allowed_signatures" to "com.x,ff;")
        assertEquals("com.x,ff;$BRIDGE_PACKAGE,abcd", bridgeFlags(prefs, "abcd")!!["bugle_phenotype__external_api_extra_allowed_signatures"])
        val other = phenotype + ("bugle_phenotype__external_api_extra_allowed_signatures" to "com.x,ff")
        assertEquals("com.x,ff;$BRIDGE_PACKAGE,abcd", bridgeFlags(other, "abcd")!!["bugle_phenotype__external_api_extra_allowed_signatures"])
    }

    @Test fun neverAddsTheSignatureTwice() {
        val prefs = phenotype + ("bugle_phenotype__external_api_extra_allowed_signatures" to "$BRIDGE_PACKAGE,abcd")
        assertEquals("$BRIDGE_PACKAGE,abcd", bridgeFlags(prefs, "abcd")!!["bugle_phenotype__external_api_extra_allowed_signatures"])
    }

    @Test fun theOriginalMapIsNotTouched() {
        val prefs = HashMap(phenotype)
        bridgeFlags(prefs, "abcd")
        assertEquals(HashMap(phenotype), prefs)
        val out = bridgeFlags(prefs, "abcd")!!
        assertSame(true, out["bugle_phenotype__enable_andromeda_mde"])
    }

    /** The certificate in the whitelist is the one of the release signing key, which signs dev.bold7.messages. */
    @Test fun signatureIsTheSigningKeys() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val props = File(root, "local.properties").takeIf { it.exists() }?.reader()?.use { r -> Properties().apply { load(r) } }
        // keys/bold7.jks, or the former keys/cascade.jks as the build reads it.
        val name = if (File(root, "keys/bold7.jks").exists()) "bold7" else "cascade"
        val password = props?.getProperty("$name.keystore.password") ?: return // no key on this machine
        val store = KeyStore.getInstance("PKCS12").apply { File(root, "keys/$name.jks").inputStream().use { load(it, password.toCharArray()) } }
        val der = store.getCertificate(store.aliases().nextElement()).encoded
        assertEquals(der.joinToString("") { "%02x".format(it) }, BRIDGE_SIGNATURE)
    }

    @Test fun onlyWithBold7MountedNoFaultAndTheServiceThere() {
        assertEquals(true, bridgeAllowed(serviceClass = true, faultInjected = false, bold7Mounted = true))
        assertEquals(false, bridgeAllowed(serviceClass = false, faultInjected = false, bold7Mounted = true))
        assertEquals(false, bridgeAllowed(serviceClass = true, faultInjected = true, bold7Mounted = true))
        assertEquals(false, bridgeAllowed(serviceClass = true, faultInjected = false, bold7Mounted = false))
    }

    @Test fun theModuleStaysInSystemUiAndMessagesMainProcessOnly() {
        assertEquals(ModuleProcess.SYSTEMUI, moduleProcess("com.android.systemui"))
        assertEquals(ModuleProcess.MESSAGES, moduleProcess("com.google.android.apps.messaging"))
        assertEquals(ModuleProcess.NONE, moduleProcess("com.google.android.apps.messaging:rcs"))
        assertEquals(ModuleProcess.NONE, moduleProcess("com.android.phone"))
    }

    @Test fun theConsentPageGoesToTheLauncherAlone() {
        assertEquals(true, consentPageFor("dev.bold7.launcher"))
        assertEquals(false, consentPageFor(null))
        assertEquals(false, consentPageFor("dev.bold7.messages"))
        assertEquals(false, consentPageFor("com.android.shell"))
    }

    /** The permission the broadcast needs is the launcher's, signature, and the launcher alone asks it. */
    @Test fun theConsentRequestNeedsTheLaunchersSignaturePermission() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val launcher = File(root, "apps/launcher/src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("""<permission\s+android:name="$OPEN_CONSENT_PERMISSION"\s+android:protectionLevel="signature" />""") in launcher)
        val askers = File(root, "apps").listFiles()!!.filter { "\"$OPEN_CONSENT_PERMISSION\"" in (File(it, "src/main/AndroidManifest.xml").takeIf(File::exists)?.readText() ?: "") }
        assertEquals(listOf("launcher"), askers.map { it.name })
        assertTrue("\"$OPEN_CONSENT_ACTION\"" in File(root, "apps/launcher/src/main/kotlin/dev/bold7/launcher/setup/TextSetupDevice.kt").readText())
    }
}
