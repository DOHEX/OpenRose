plugins {
    `kotlin-dsl`
}

// The root build puts these plugins on the classpath (`apply false`); compiling against them
// here keeps a single copy loaded for every project.
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
        register("readmeSync") {
            id = libs.plugins.openrose.readme.sync.get().pluginId
            implementationClass = "ReadmeSyncConventionPlugin"
        }
    }
}
