plugins {
    alias(libs.plugins.openrose.jvm.library)
}
dependencies {
    api(projects.core)
    implementation(libs.bcpkix)
    testImplementation(kotlin("test-junit"))
}
