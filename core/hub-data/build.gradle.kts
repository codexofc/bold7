plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android { namespace = "dev.bold7.hub.data" }

// Each version's schema, committed: a migration is written and tested against them.
room { schemaDirectory("$projectDir/schemas") }

dependencies {
    // The Hub's store.
    implementation("androidx.room:room-runtime:2.8.5")
    // The made-up inbox (ShowcaseItems.kt), read in place of the Hub by the apps that show it.
    implementation(project(":core:showcase"))
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Room's real queries on the JVM, on SQLite built for the Mac: no Robolectric, no phone.
    // Tests only, Apache 2.0.
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
}
