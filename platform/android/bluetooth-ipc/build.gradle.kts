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
    implementation(libs.androidx.core)
    api(projects.platform.android.integration)
    api(projects.shared.circulation)
    implementation(projects.platform.android.ffi)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
