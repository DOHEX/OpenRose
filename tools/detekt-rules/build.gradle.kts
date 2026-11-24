plugins {
    alias(libs.plugins.openrose.jvm.library)
}
dependencies {
    compileOnly(libs.detekt.api)
    compileOnly(libs.detekt.psi.utils)
}
