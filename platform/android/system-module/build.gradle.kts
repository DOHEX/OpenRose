plugins {
    alias(libs.plugins.openrose.android.library)
    alias(libs.plugins.openrose.rust.ffi)
}
android {
    namespace = "io.github.dohex.openrose.module"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}
dependencies {
    implementation(projects.platform.android.bluetoothIpc)
    implementation(projects.platform.android.ffi)
    implementation(projects.shared.circulationLan)
    implementation(libs.androidx.core)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.libxposed.api)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
