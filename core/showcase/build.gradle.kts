plugins { id("com.android.library") }

// The showcase's made-up people, messages, calls, appointments, tasks and memos, and their
// avatars (vectors drawn for Bold7), shown by the apps instead of the user's data while the
// global setting bold7_showcase is 1: for screenshots and videos only.
android { namespace = "dev.bold7.showcase" }

dependencies {
    testImplementation(kotlin("test-junit"))
}
