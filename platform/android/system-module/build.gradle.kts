// The OpenRose module: code LSPosed loads into the scoped system processes. It cannot see the application or the
// shared UI; the application packages it with runtimeOnly. See ADR 0018.
plugins {
    alias(libs.plugins.openrose.android.library)
}
android {
    namespace = "io.github.dohex.openrose.module"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}
dependencies {
    implementation(projects.platform.android.bluetoothIpc)
    implementation(projects.shared.circulationLan)
    implementation(libs.androidx.core)
    compileOnly(libs.libxposed.api)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
