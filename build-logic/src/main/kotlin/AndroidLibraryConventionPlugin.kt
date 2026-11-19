import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android-only library on the catalog SDK levels and the shared JDK, for code that needs what the Android-KMP library
 * plugin lacks, such as AIDL. Kotlin is AGP's built-in support; the application's lint checks it as a dependency.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = libs.intVersion("android-compileSdk")
            buildToolsVersion = libs.stringVersion("android-buildTools")
            defaultConfig {
                minSdk = libs.intVersion("android-minSdk")
            }
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
        }
        configureKotlinJvm()
        configureQuality()
    }
}
