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
    }
}
// Exercise the production scanner and initialization encoder against the independent APK inventories.
tasks.named<ProcessResources>("jvmTestProcessResources") {
    from(rootProject.layout.projectDirectory.dir("docs/protocols")) {
        include("supported-devices.json", "supported-devices-3.8.6.json")
    }
}
// Android integration logic runs on the JVM and stays well covered (91.7% of lines when set).
kover {
    reports {
        verify { rule { minBound(90) } }
    }
}
