plugins { id("com.android.library") }

android { namespace = "dev.bold7.design" }

dependencies {
    testImplementation(kotlin("test-junit"))
}

// Some tests read the rest of the repository (Os7LanguageTest, the overlays' colours and spinner):
// those files are their inputs, so that a change there runs them again instead of an up-to-date skip.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree("apps") { include("*/src/main/**") },
        rootProject.fileTree("core") { include("*/src/main/**") },
        rootProject.fileTree("system") { include("sysui/src/main/**", "overlays/*/src/main/**") },
    ).withPropertyName("repositorySources").withPathSensitivity(PathSensitivity.RELATIVE)
}
