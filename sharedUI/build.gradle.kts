plugins {
    alias(libs.plugins.openrose.kmp.compose.library)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    android {
        namespace = "io.github.dohex.openrose.ui"
        androidResources.enable = true
    }
    sourceSets {
        androidMain.dependencies {
            implementation(libs.activity.compose)
        }
        commonMain.dependencies {
            api(projects.core)
            // Transitional until the Android sections move to :androidIntegrationUI (#89).
            api(projects.androidIntegration)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.material3)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.nav)
            implementation(libs.miuix.blur)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.dohex.openrose.ui.resources"
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    // Each UI test drives its own scene on a virtual clock, so test classes can run side by side.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}
