plugins { id("com.android.library") }

// The images of OS 7 read at run time by name (BBOS7-VISUEL): from the
// folder the module mounts read-only (/product/media/bold7, laid out as assets-gen/), else from the
// APK's own compiled resources (development, until the module extracts them on the phone).
android {
    namespace = "dev.bold7.theme"
    // AaptNinePatchTest compares the 9-patches of assets-gen with what aapt2 makes of them.
    testOptions.unitTests.all { it.systemProperty("bold7.root", rootDir.path) }
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

// The 9-patches the test reads: inputs, or a new import would find the test up to date.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree(rootProject.file("assets-gen")) { include("*/res/**/*.9.png") })
}
