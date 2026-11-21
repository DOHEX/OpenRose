// Android adapters both the application and the module processes run: control transports, platform facts and
// the module host IPC (AIDL and Bundle encoding). No UI and no libxposed; see ADR 0018.
plugins {
    alias(libs.plugins.openrose.android.library)
}
android {
    namespace = "io.github.dohex.openrose.platform"
    buildFeatures {
        aidl = true
    }
}
dependencies {
    api(projects.platform.android.integration)
    api(projects.shared.circulation)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
