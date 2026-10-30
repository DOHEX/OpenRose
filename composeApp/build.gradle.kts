import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    jvmToolchain(21)
    android {
        namespace = "io.github.dohex.openrose.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        androidResources.enable = true
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    sourceSets {
        androidMain.dependencies {
            implementation(libs.activity.compose)
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(compose.components.resources)
            implementation(libs.material3)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.nav)
            implementation(libs.miuix.blur)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.dohex.openrose.ui.resources"
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
