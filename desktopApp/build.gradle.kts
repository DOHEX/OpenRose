import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.openrose.desktop.application)
}
dependencies {
    implementation(projects.sharedUI)
    implementation(projects.transportBluez)
    implementation(compose.desktop.currentOs)
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.tao)
    implementation(libs.nucleus.autolaunch)
    implementation(libs.composenativetray.app)
    implementation(libs.dbus.java.core)
    runtimeOnly(libs.dbus.java.native.unixsocket)
    // dbus-java logs through slf4j-api only; simplelogger.properties keeps its warnings and errors on stderr.
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}
nucleus {
    application {
        mainClass = "io.github.dohex.openrose.desktop.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            // dbus-java authenticates Unix sockets through com.sun.security.auth.module.UnixSystem.
            modules("jdk.security.auth")
            // Each host builds only its own formats: Linux x64 the four packages, Windows the MSI.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Pacman, TargetFormat.AppImage, TargetFormat.Msi)
            packageName = "OpenRose"
            packageVersion = providers.gradleProperty("releaseVersion").getOrElse("0.1.0")
            homepage = "https://github.com/DOHEX/OpenRose"
            linux {
                packageName = "openrose"
                debMaintainer = "DOHEX <53633138+DOHEX@users.noreply.github.com>"
                // Custom lists replace electron-builder defaults; include the JVM/GTK runtime too.
                // The tray's native library talks to the tray host through sd-bus (libsystemd.so.0).
                debDepends = listOf("bluez", "libgtk-3-0", "libegl1", "libgl1", "libfontconfig1", "libsystemd0")
                rpmRequires = listOf("bluez", "gtk3", "mesa-libEGL", "mesa-libGL", "fontconfig", "systemd-libs")
                pacmanDepends = listOf("bluez", "gtk3", "libglvnd", "fontconfig", "systemd-libs")
            }
        }
    }
}
