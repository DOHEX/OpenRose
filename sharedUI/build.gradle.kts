plugins {
    alias(libs.plugins.openrose.kmp.compose.library)
    alias(libs.plugins.kotlin.serialization)
}
// 关于 shows the application version; build-logic derives it from version.txt.
val generateAppVersion = tasks.register<GenerateAppVersionSource>("generateAppVersion") {
    packageName.set("io.github.dohex.openrose.ui")
    versionName.set(appVersion.name)
    outputDirectory.set(layout.buildDirectory.dir("generated/appVersion/commonMain"))
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
        commonMain {
            kotlin.srcDir(generateAppVersion)
        }
        commonMain.dependencies {
            api(projects.core)
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
        jvmTest {
            kotlin.srcDir("src/jvmTestFixtures/kotlin")
            dependencies { implementation(compose.desktop.currentOs) }
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.dohex.openrose.ui.resources"
    publicResClass = true
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    // Each UI test drives its own scene on a virtual clock, so test classes can run side by side.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}
