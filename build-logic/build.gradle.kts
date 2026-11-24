plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.plugins.kotlin.multiplatform.asDependency())
    compileOnly(libs.plugins.android.application.asDependency())
    compileOnly(libs.plugins.nucleus.asDependency())
    compileOnly(libs.plugins.detekt.asDependency())
    compileOnly(libs.plugins.kover.asDependency())
}

fun Provider<PluginDependency>.asDependency(): Provider<String> = map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

gradlePlugin {
    plugins {
        register("detekt") {
            id = libs.plugins.openrose.detekt.get().pluginId
            implementationClass = "DetektConventionPlugin"
        }
        register("kover") {
            id = libs.plugins.openrose.kover.get().pluginId
            implementationClass = "KoverConventionPlugin"
        }
        register("kmpLibrary") {
            id = libs.plugins.openrose.kmp.library.get().pluginId
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpComposeLibrary") {
            id = libs.plugins.openrose.kmp.compose.library.get().pluginId
            implementationClass = "KmpComposeLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.openrose.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("desktopApplication") {
            id = libs.plugins.openrose.desktop.application.get().pluginId
            implementationClass = "DesktopApplicationConventionPlugin"
        }
        register("androidApplication") {
            id = libs.plugins.openrose.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.openrose.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
