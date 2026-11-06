// Loads every plugin once for the whole build; modules apply them through build-logic conventions.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.hot.reload) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.nucleus) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
}

// ktlint formats every Kotlin source and build script; rules come from .editorconfig.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath(rootProject.file(".editorconfig"))
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}

// One coverage report over every module's JVM tests. Floors sit just under the coverage measured when they
// were set (total 56.8% of lines); raise them as coverage grows, never lower them to pass.
dependencies {
    kover(projects.core)
    kover(projects.androidIntegration)
    kover(projects.sharedUI)
    kover(projects.androidIntegrationUI)
    kover(projects.androidApp)
    kover(projects.desktopApp)
    kover(projects.transportBluez)
}
kover {
    reports {
        verify { rule { minBound(55) } }
    }
}

// Shared control and Compose behavior run on the host JVM, without hardware.
tasks.register("offlineCheck") {
    group = "verification"
    description = "Run hardware-independent checks (add --offline after dependencies are cached)."
    dependsOn(
        ":core:jvmTest", ":androidIntegration:jvmTest", ":sharedUI:jvmTest", ":androidIntegrationUI:jvmTest",
        ":desktopApp:test", ":androidApp:testDebugUnitTest", ":transportBluez:test",
        ":desktopApp:compileKotlin", ":androidApp:compileDebugKotlin",
        ":androidApp:lintDebug", "spotlessCheck",
        "koverVerify", ":core:koverVerify", ":androidIntegration:koverVerify",
    )
    dependsOn(subprojects.map { "${it.path}:detekt" })
}
