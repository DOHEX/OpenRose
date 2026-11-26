import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.openrose.kmp.library)
}
kotlin {
    android {
        namespace = "io.github.dohex.openrose.androidintegration"
    }
    sourceSets {
        commonMain.dependencies { api(projects.shared.headset) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(projects.platform.android.ffi)
        }
    }
}
tasks.named<ProcessResources>("jvmTestProcessResources") {
    from(rootProject.layout.projectDirectory.dir("docs/protocols")) {
        include("supported-devices.json", "supported-devices-3.8.6.json")
    }
}
tasks.named<Test>("jvmTest") {
    dependsOn(":platform:android:ffi:buildHostRustFfi")
    systemProperty("jna.library.path", rootProject.layout.projectDirectory.dir("target/debug").asFile.absolutePath)
}
kover {
    reports {
        verify { rule { minBound(90) } }
    }
}
