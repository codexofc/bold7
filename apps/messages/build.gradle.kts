plugins { id("com.android.application") }

// The icon strips, scroll arrows and short menu images of Messages, out of git (provenance in
// assets-private/MANIFEST.md), copied into this checkout's assets-gen by tools/import-assets.sh messages.
// Read by name at run time: from the folder the module mounts, else from these compiled
// resources, which only the development build carries. A public build (-Pbold7.public=true)
// carries none of them.
val privateRes = rootProject.file("assets-gen/messages/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

// Messages of BBOS 7.1, the unified inbox (docs/BBOS7-VISUEL.md §15): an
// ordinary app reading the Hub's store of apps/hub (READ_HUB, a signature permission).
android {
    namespace = "dev.bold7.messages"
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
    buildTypes.getByName("release").proguardFiles("proguard-rules.pro")
}

if (!public) tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: menus, list header, dialogs, search field.
    implementation(project(":core:ui"))
    // The other apps' BBOS 7 icons in the list's rows, drawn as the home's grid draws them (B14).
    implementation(project(":core:icons"))
    // The Hub's model, its pure list and its provider's contract; the store stays in apps/hub's process.
    implementation(project(":core:hub-data"))
    // The showcase's made-up people and messages, shown instead of the user's while bold7_showcase is 1.
    implementation(project(":core:showcase"))
    // The feed's work off the main thread and its StateFlow, as the launcher's. Apache 2.0.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Google Messages' ExternalMessagingApiService speaks gRPC over binder (reverse-engineered, see Proto.kt):
    // grpc-binder and grpc-stub, Apache 2.0 (with grpc-api, grpc-core and Guava, Apache 2.0 too).
    // The protobuf messages are hand-written (Proto.kt): no protobuf library.
    implementation("io.grpc:grpc-binder:1.85.1")
    implementation("io.grpc:grpc-stub:1.85.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
