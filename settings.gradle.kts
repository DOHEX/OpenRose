pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "OpenRose"
include(
    ":androidApp",
    ":desktopApp",
    ":shared:headset",
    ":shared:ui",
    ":shared:circulation",
    ":shared:circulation-lan",
    ":platform:android:integration",
    ":platform:android:ffi",
    ":platform:android:bluetooth-ipc",
    ":platform:android:system-module",
    ":platform:android:ui",
    ":platform:linux",
    ":platform:windows",
    ":tools:ui-sandbox",
    ":tools:ui-test-support",
    ":tools:detekt-rules",
)
