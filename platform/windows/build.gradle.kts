plugins {
    alias(libs.plugins.openrose.jvm.library)
}

dependencies {
    api(projects.shared.headset)
    api(projects.shared.circulation)
    implementation(libs.coroutines.core)
    testImplementation(testFixtures(projects.shared.circulationLan))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}

// desktopApp installs the helper beside the application; desktop run and the MSI both start it from there.
val winrtHelper = configurations.consumable("winrtHelper") {
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(WINRT_HELPER_USAGE)) }
}
if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    val targetDir = layout.buildDirectory.dir("cargo")
    val helper = targetDir.map { it.file("release/openrose-winrt.exe") }
    val buildWinrt = tasks.register<Exec>("buildWinrt") {
        inputs.files(fileTree("native") { exclude("target/**") })
        outputs.file(helper)
        workingDir("native")
        commandLine("cargo", "build", "--release", "--locked", "--target-dir", targetDir.get().asFile)
    }
    winrtHelper.configure { outgoing.artifact(helper) { builtBy(buildWinrt) } }
}
