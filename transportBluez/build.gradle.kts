plugins {
    alias(libs.plugins.openrose.jvm.library)
}

dependencies {
    api(projects.core)
    implementation(libs.coroutines.core)
    implementation(libs.bluez.dbus)
    runtimeOnly(libs.dbus.java.native.unixsocket)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}
