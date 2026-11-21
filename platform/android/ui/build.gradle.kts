plugins {
    alias(libs.plugins.openrose.kmp.compose.library)
}

kotlin {
    android {
        namespace = "io.github.dohex.openrose.androidintegration.ui"
        androidResources.enable = true
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.ui)
            api(projects.platform.android.integration)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.material3)
            implementation(libs.miuix.ui)
        }
        jvmTest {
            dependencies {
                implementation(projects.tools.uiTestSupport)
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.compose.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.dohex.openrose.androidintegration.ui.resources"
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}
