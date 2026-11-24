import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.openrose.desktop.application)
}
val winrtHelper = configurations.dependencyScope("winrtHelper")
val winrtHelperFiles = configurations.resolvable("winrtHelperFiles") {
    extendsFrom(winrtHelper.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(WINRT_HELPER_USAGE)) }
}
val appResources = tasks.register<Sync>("prepareOpenRoseAppResources") {
    from(winrtHelperFiles) { into("windows") }
    into(layout.buildDirectory.dir("app-resources"))
}
dependencies {
    add(winrtHelper.name, projects.platform.windows)
    implementation(projects.shared.ui)
    implementation(libs.zxing.core)
    implementation(projects.platform.linux)
    implementation(projects.platform.windows)
    implementation(projects.shared.circulationLan)
    implementation(libs.jmdns)
    implementation(compose.desktop.currentOs)
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.tao)
    implementation(libs.nucleus.autolaunch)
    implementation(libs.composenativetray.app)
    implementation(libs.dbus.java.core)
    runtimeOnly(libs.dbus.java.native.unixsocket)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}
nucleus {
    application {
        mainClass = "io.github.dohex.openrose.desktop.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            appResourcesRootDir.set(layout.dir(appResources.map { it.destinationDir }))
            modules("jdk.security.auth", "jdk.crypto.ec")
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Pacman, TargetFormat.AppImage, TargetFormat.Msi)
            packageName = "OpenRose"
            vendor = "DOHEX"
            description = "Third-party control client for ROSE / ROSESELSA earphones"
            homepage = "https://github.com/DOHEX/OpenRose"
            linux {
                iconFile.set(project.file("icons/openrose.png"))
                packageName = "openrose"
                debMaintainer = "DOHEX <53633138+DOHEX@users.noreply.github.com>"
                debDepends = listOf("bluez", "pulseaudio-utils", "libgtk-3-0", "libegl1", "libgl1", "libfontconfig1", "libsystemd0")
                rpmRequires = listOf("bluez", "pulseaudio-utils", "gtk3", "mesa-libEGL", "mesa-libGL", "fontconfig", "systemd-libs")
                pacmanDepends = listOf("bluez", "libpulse", "gtk3", "libglvnd", "fontconfig", "systemd-libs")
                appImage {
                    desktopEntries = mapOf("Name" to "OpenRose")
                }
            }
            windows {
                iconFile.set(project.file("icons/openrose.png"))
                upgradeUuid = "07a3acd9-577c-4b4e-9e8e-dbca1d107e25"
                msi {
                    perMachine = false
                    createDesktopShortcut = false
                }
            }
        }
    }
}
