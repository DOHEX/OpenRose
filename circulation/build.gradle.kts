plugins {
    `java-test-fixtures`
    alias(libs.plugins.openrose.jvm.library)
}
dependencies {
    api(projects.core)
    implementation(libs.bcprov)
    testImplementation(kotlin("test-junit"))
}
