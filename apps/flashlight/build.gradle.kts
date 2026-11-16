plugins { id("com.android.application") }

// Flashlight of Bold7: an ordinary app, no permission (the torch needs none),
// nothing stored, no image of the theme: everything is drawn.
android {
    namespace = "dev.bold7.flashlight"
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: the menu, the title bar, the status message.
    implementation(project(":core:ui"))
    testImplementation(kotlin("test-junit"))
}
