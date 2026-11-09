// Development-only window that shows the shared UI for any 型号配置, platform and component style on fabricated
// state, without earphones or a platform transport. It is never packaged or released.
plugins {
    alias(libs.plugins.openrose.jvm.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}
dependencies {
    implementation(projects.androidIntegrationUI)
    implementation(projects.desktopApp)
    // The desktop app's Tao window backend owns Dispatchers.Main, so the sandbox opens its windows the same way.
    implementation(compose.desktop.currentOs)
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.tao)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.material3)
}
// `run` starts it; the bundled Compose Hot Reload adds `hotRun`, which reloads edits to this and every UI module.
compose.desktop {
    application {
        mainClass = "io.github.dohex.openrose.sandbox.SandboxMainKt"
        // The same native access the desktop app grants its window backend.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
