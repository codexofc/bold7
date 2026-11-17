import java.util.Properties

plugins { id("com.android.application") }

// Browser sign-in (OAuth): Bold7's client ids, and Google's
// Desktop client secret, from local.properties, never in git. Absent, the build goes on and
// Email Setup says that browser sign-in is off.
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }
fun oauth(key: String) = "\"" + local.getProperty("bold7.oauth.$key", "").replace("\\", "").replace("\"", "") + "\""

android {
    namespace = "dev.bold7.hub"
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField("String", "OAUTH_GOOGLE_ID", oauth("google.id"))
        buildConfigField("String", "OAUTH_GOOGLE_SECRET", oauth("google.secret"))
        buildConfigField("String", "OAUTH_MICROSOFT_ID", oauth("microsoft.id"))
    }
    // In both jars of mime4j (core/mail): AGP refuses the duplicate.
    packaging.resources.excludes += "META-INF/DEPENDENCIES"
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The Hub's store and its counters' provider, which this process owns.
    implementation(project(":core:hub-data"))
    // Mail: IMAP, IDLE and SMTP, the accounts sealed by the Keystore.
    // This process holds the accounts and the network; Messages asks it (MailContract).
    implementation(project(":core:mail"))
    // The Bold7 logo as its icon (Settings > Apps, its notifications).
    implementation(project(":core:brand"))
    // runBlocking on the sources' own thread, around the store's suspend writes. Apache 2.0,
    // already in the launcher.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}
