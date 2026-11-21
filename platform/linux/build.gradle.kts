plugins {
    alias(libs.plugins.openrose.jvm.library)
}

dependencies {
    api(projects.shared.headset)
    api(projects.shared.circulation)
    implementation(libs.coroutines.core)
    implementation(libs.bluez.dbus)
    runtimeOnly(libs.dbus.java.native.unixsocket)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}
