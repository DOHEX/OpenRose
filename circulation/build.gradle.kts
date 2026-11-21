plugins {
    alias(libs.plugins.openrose.jvm.library)
}
dependencies {
    api(projects.core)
    implementation(libs.bcprov)
    testImplementation(kotlin("test-junit"))
}
