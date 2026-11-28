plugins {
    alias(libs.plugins.openrose.kmp.library)
    alias(libs.plugins.openrose.rust.ffi)
}

val generatedBindingsDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val generatedSimulatorBindingsDir = layout.buildDirectory.dir("generated/uniffi/simulator")

val generateUniffiBindings = registerRustFfiTask<GenerateUniffiBindingsTask>("generateUniffiBindings") {
    libraryFile.set(hostRustFfiLibrary())
    uniffiConfigs.from(
        rootProject.layout.projectDirectory.file("crates/headset/uniffi.toml"),
        rootProject.layout.projectDirectory.file("crates/control/uniffi.toml"),
        rootProject.layout.projectDirectory.file("crates/circulation/uniffi.toml"),
        rootProject.layout.projectDirectory.file("crates/ffi/uniffi.toml"),
    )
    crates.set(listOf("openrose_headset", "openrose_control", "openrose_circulation", "openrose_ffi"))
    outputDir.set(generatedBindingsDir)
}

val generateSimulatorBindings = registerRustFfiTask<GenerateUniffiBindingsTask>("generateSimulatorBindings") {
    libraryFile.set(hostRustFfiLibrary())
    uniffiConfigs.from(rootProject.layout.projectDirectory.file("crates/ffi-simulator/uniffi.toml"))
    crates.set(listOf("openrose_ffi_simulator"))
    outputDir.set(generatedSimulatorBindingsDir)
}

kotlin {
    android {
        namespace = "io.github.dohex.openrose.ffi"
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.files.add(file("consumer-rules.pro"))
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(generateUniffiBindings.map { it.outputDir })
            dependencies {
                api(libs.coroutines.core)
                api(projects.shared.headset)
                compileOnly(libs.jna)
            }
        }
        jvmMain {
            kotlin.srcDir(generateSimulatorBindings.map { it.outputDir })
            dependencies {
                implementation(libs.jna)
            }
        }
        androidMain.dependencies {
            implementation("${libs.jna.get()}@aar")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                packages("uniffi")
            }
        }
    }
}
