# Moshi reads this class by reflection, for the server's answer to a failed XOAUTH2 (upstream
# keeps it in app-thunderbird/proguard-rules.pro).
-keep class com.fsck.k9.mail.oauth.XOAuth2Response { *; }

# Annotations only, compileOnly in build.gradle.kts: nothing reads them at run time.
-dontwarn androidx.annotation.**
-dontwarn org.jetbrains.annotations.**
