// Loads every plugin once for the whole build; modules apply them through build-logic conventions.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.nucleus) apply false
}
// Shared control and Compose behavior run on the host JVM, without hardware.
tasks.register("offlineCheck") {
    group = "verification"
    description = "Run hardware-independent checks (add --offline after dependencies are cached)."
    dependsOn(":core:jvmTest", ":sharedUI:jvmTest", ":desktopApp:compileKotlin", ":androidApp:compileDebugKotlin")
}
