plugins {
    alias(libs.plugins.openrose.kmp.library)
    alias(libs.plugins.openrose.rust.ffi)
}
kotlin {
    android {
        namespace = "io.github.dohex.openrose.androidintegration"
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.headset)
            implementation(projects.platform.android.ffi)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
tasks.named<ProcessResources>("jvmTestProcessResources") {
    from(rootProject.layout.projectDirectory.dir("docs/protocols")) {
        include("supported-devices.json", "supported-devices-3.8.6.json")
    }
}
kover {
    reports {
        verify { rule { minBound(90) } }
    }
}
