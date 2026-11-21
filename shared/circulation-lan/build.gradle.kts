plugins {
    `java-test-fixtures`
    alias(libs.plugins.openrose.jvm.library)
}
dependencies {
    api(projects.shared.circulation)
    implementation(libs.bcprov)
    testImplementation(kotlin("test-junit"))
}
