plugins {
    alias(libs.plugins.openrose.jvm.library)
}

dependencies {
    api(projects.core)
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}

// The same helper is included in desktop run, the application JAR and the MSI.
if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    val targetDir = layout.buildDirectory.dir("cargo")
    val buildWinrt = tasks.register<Exec>("buildWinrt") {
        inputs.files(fileTree("native") { exclude("target/**") })
        outputs.file(targetDir.map { it.file("release/openrose-winrt.exe") })
        workingDir("native")
        commandLine("cargo", "build", "--release", "--locked", "--target-dir", targetDir.get().asFile)
    }
    tasks.processResources {
        dependsOn(buildWinrt)
        from(targetDir.map { it.file("release/openrose-winrt.exe") })
    }
}
