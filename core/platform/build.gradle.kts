import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { id("com.android.library") }

android {
    namespace = "dev.bold7.platform"
    buildFeatures { aidl = true }
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

// Signatures of the Q25 itself, hidden members included, pulled read-only by tools/stubs.sh. Never packaged.
// Checked when compiling only (requireStubs), so clean, IDE sync and the other modules work
// without it; declared only if present, or resolving the classpath fails first, unexplained.
val frameworkStubs = rootProject.file("stubs/framework.jar")
fun Task.requireStubs(stubs: File) = doFirst {
    check(stubs.exists()) { "Missing $stubs: run tools/stubs.sh with the Q25 on adb" }
}

dependencies {
    if (frameworkStubs.exists()) compileOnly(files(frameworkStubs))
    // AOSP's GroupedRecentTaskInfo.java uses androidx @NonNull/@Nullable; annotations only.
    compileOnly("androidx.annotation:annotation:1.9.1")
    // OS 7's language (OS7_LOCALE), which every app's process speaks (speakOs7Language).
    implementation(project(":core:design"))
    testImplementation(kotlin("test-junit"))
}

// 1. android.jar holds the public subset of the same classes (InputManager, TaskInfo...) and
//    AGP puts it first on both classpaths: framework.jar must come before it, or the hidden
//    members do not resolve (javac ignores bootstrapClasspath here, AGP uses --system).
// 2. src/main/aidl/android/** only declares framework interfaces for the AIDL imports. AGP
//    generates an empty interface from each anyway, which the Kotlin compiler resolves before
//    framework.jar's (an exclude on its sources does not reach its Java symbol resolution):
//    'onAnimationStart' overrides nothing. They are deleted right after generation.
// In afterEvaluate because AGP configures its tasks after a plain configureEach and would
// undo these changes.
afterEvaluate {
    tasks.matching { it.name.matches(Regex("compile.*Aidl")) }.configureEach {
        val generated = outputs.files
        doLast { generated.forEach { File(it, "android").deleteRecursively() } }
    }
    tasks.withType<KotlinCompile>().configureEach {
        val agp = libraries.from.toList()
        libraries.setFrom(frameworkStubs, agp)
        requireStubs(frameworkStubs)
    }
    tasks.withType<JavaCompile>().configureEach {
        classpath = files(frameworkStubs) + classpath
        requireStubs(frameworkStubs)
    }
}
