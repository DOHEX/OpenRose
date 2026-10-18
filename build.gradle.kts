plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}
// The public control-session contract runs on the host JVM, without hardware.
tasks.register("offlineCheck") {
    group = "verification"
    description = "Run hardware-independent checks (add --offline after dependencies are cached)."
    dependsOn(":core:jvmTest", ":composeApp:compileKotlinJvm", ":desktopApp:compileKotlinJvm", ":androidApp:compileDebugKotlin")
}
