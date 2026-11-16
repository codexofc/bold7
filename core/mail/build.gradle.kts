plugins { id("com.android.library") }

android {
    namespace = "dev.bold7.mail"
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

// src/main/java: Thunderbird for Android's protocol layer, vendored. Its
// dependencies are the ones upstream declares for mail/common, mail/protocols/imap and
// mail/protocols/smtp at the same tag, minus httpclient5 and okhttp.
dependencies {
    implementation("org.apache.james:apache-mime4j-core:0.8.15")
    implementation("org.apache.james:apache-mime4j-dom:0.8.15")
    implementation("com.squareup.okio:okio:3.18.2")
    implementation("commons-io:commons-io:2.22.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.jcraft:jzlib:1.0.7")
    implementation("com.beetstra.jutf7:jutf7:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("org.jetbrains:annotations:26.1.0")
    compileOnly("androidx.annotation:annotation:1.10.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // In-process IMAP (IDLE included) and SMTP server on localhost: no test reaches a real server.
    testImplementation("com.icegreen:greenmail:2.1.8")
}
