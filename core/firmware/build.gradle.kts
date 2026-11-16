plugins { id("com.android.library") }

// The extractor of the installer: reads the official file of
// the Bold 9900 and writes the assets Bold7 draws with. Pure Kotlin and the JDK, no android.*: the
// module's customize.sh runs its main() in root through app_process.
android {
    namespace = "dev.bold7.firmware"
    // The tests that read the real firmware find it, and this checkout's assets-gen/, from here;
    // they are skipped when the firmware is missing.
    testOptions.unitTests.all { it.systemProperty("bold7.root", rootDir.path) }
}

dependencies {
    testImplementation(kotlin("test-junit"))
}
