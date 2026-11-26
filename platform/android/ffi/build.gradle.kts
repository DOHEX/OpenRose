import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.openrose.kmp.library)
}

val workspaceRoot = rootProject.layout.projectDirectory
val hostTargetDir = workspaceRoot.dir("target/debug")
val hostLibraryFile = hostTargetDir.file(hostRustLibraryFileName())
val generatedBindingsDir = layout.buildDirectory.dir("generated/uniffi/kotlin")

val buildHostRustFfi = tasks.register<BuildHostRustFfiTask>("buildHostRustFfi") {
    workspaceRoot.set(rootProject.layout.projectDirectory)
    offline.set(gradle.startParameter.isOffline)
    rustInputs.from(rustFfiSourceInputs())
    outputLibrary.set(hostLibraryFile)
}

val generateUniffiBindings = tasks.register<GenerateUniffiBindingsTask>("generateUniffiBindings") {
    dependsOn(buildHostRustFfi)
    workspaceRoot.set(rootProject.layout.projectDirectory)
    offline.set(gradle.startParameter.isOffline)
    libraryFile.set(buildHostRustFfi.flatMap { it.outputLibrary })
    uniffiConfigs.from(
        rootProject.layout.projectDirectory.file("crates/headset/uniffi.toml"),
        rootProject.layout.projectDirectory.file("crates/control/uniffi.toml"),
        rootProject.layout.projectDirectory.file("crates/ffi/uniffi.toml"),
    )
    outputDir.set(generatedBindingsDir)
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
                compileOnly(libs.jna)
            }
        }
        jvmMain.dependencies {
            implementation(libs.jna)
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

tasks.named<Test>("jvmTest") {
    dependsOn(buildHostRustFfi)
    systemProperty("jna.library.path", hostTargetDir.asFile.absolutePath)
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
