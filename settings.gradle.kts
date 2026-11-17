pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bold7"

// core/ holds the shared libraries, apps/ the apps, system/ SystemUI, the module and one RRO APK per
// target under system/overlays, tools/ the test app.
include(
    ":core:design", ":core:theme", ":core:ui", ":core:icons", ":core:firmware", ":core:platform", ":core:hub-data", ":core:mail", ":core:showcase", ":core:brand",
    ":apps:launcher", ":apps:hub", ":apps:messages", ":apps:phone",
    ":apps:contacts", ":apps:calendar", ":apps:clock", ":apps:settings", ":apps:saisie",
    ":apps:calculator", ":apps:memopad", ":apps:tasks", ":apps:flashlight", ":apps:pictures", ":apps:help",
    ":system:sysui", ":system:module",
    ":system:overlays:recents", ":system:overlays:android", ":system:overlays:systemui",
    ":system:overlays:permissioncontroller", ":system:overlays:settings", ":system:overlays:names",
    ":tools:essai",
)
