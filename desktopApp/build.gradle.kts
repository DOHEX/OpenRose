import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}
kotlin {
    jvmToolchain(21)
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":composeApp"))
            implementation(compose.desktop.currentOs)
        }
    }
}
compose.desktop {
    application {
        mainClass = "io.github.dohex.openrose.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "OpenRose"
            packageVersion = "0.1.0"
        }
    }
}
