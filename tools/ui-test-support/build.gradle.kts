plugins {
    alias(libs.plugins.openrose.jvm.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}
dependencies {
    api(projects.shared.ui)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.test)
    implementation(libs.material3)
    implementation(libs.miuix.ui)
}
