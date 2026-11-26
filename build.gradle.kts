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
    alias(libs.plugins.openrose.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
    alias(libs.plugins.openrose.kover)
}

detekt {
    source.setFrom(layout.projectDirectory.dir("build-logic/src"))
}

spotless {
    kotlin {
        target(
            fileTree(rootDir) {
                include("**/*.kt")
                exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/node_modules/**")
            },
        )
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath(rootProject.file(".editorconfig"))
    }
    kotlinGradle {
        target(
            fileTree(rootDir) {
                include("**/*.gradle.kts")
                exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/node_modules/**")
            },
        )
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}

dependencies {
    kover(projects.shared.headset)
    kover(projects.platform.android.integration)
    kover(projects.platform.android.ffi)
    kover(projects.shared.ui)
    kover(projects.platform.android.ui)
    kover(projects.platform.android.bluetoothIpc)
    kover(projects.platform.android.systemModule)
    kover(projects.androidApp)
    kover(projects.desktopApp)
    kover(projects.platform.linux)
    kover(projects.platform.windows)
    kover(projects.shared.circulationLan)
    kover(projects.shared.circulation)
}
kover {
    reports {
        filters {
            excludes {
                packages("uniffi")
            }
        }
        verify { rule { minBound(55) } }
    }
}

tasks.register("offlineCheck") {
    group = "verification"
    description = "Run hardware-independent checks (add --offline after dependencies are cached)."
    dependsOn(
        ":shared:headset:jvmTest", ":platform:android:ffi:jvmTest", ":platform:android:integration:jvmTest", ":shared:ui:jvmTest", ":platform:android:ui:jvmTest",
        ":desktopApp:test", ":platform:android:bluetooth-ipc:testDebugUnitTest", ":platform:android:system-module:testDebugUnitTest",
        ":androidApp:testDebugUnitTest", ":platform:linux:test", ":platform:windows:test", ":shared:circulation-lan:test",
        ":shared:circulation:jvmTest", ":tools:ui-sandbox:test", ":tools:ui-test-support:test",
        ":desktopApp:compileKotlin", ":platform:android:system-module:compileDebugKotlin", ":androidApp:compileDebugKotlin", ":tools:ui-sandbox:compileKotlin",
        ":androidApp:lintDebug", "spotlessCheck", ":detekt",
        ":koverVerifyCoverage", ":shared:headset:koverVerifyCoverage", ":platform:android:integration:koverVerifyCoverage",
    )
    dependsOn(subprojects.filter { it.childProjects.isEmpty() }.map { "${it.path}:detekt" })
}
