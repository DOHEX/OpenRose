plugins {
    alias(libs.plugins.openrose.jvm.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}
dependencies {
    implementation(projects.platform.android.ui)
    implementation(projects.shared.ui)
    implementation(compose.desktop.currentOs)
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.tao)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.material3)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test)
}
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
compose.desktop {
    application {
        mainClass = "io.github.dohex.openrose.sandbox.SandboxMainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
