import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android application on the catalog SDK levels and the shared JDK; Kotlin is AGP's built-in support.
 * Lint also checks the library modules the app depends on, fails on warnings, and freezes existing
 * findings in the module's `lint-baseline.xml`.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = libs.intVersion("android-compileSdk")
            buildToolsVersion = libs.stringVersion("android-buildTools")
            defaultConfig {
                minSdk = libs.intVersion("android-minSdk")
                targetSdk = libs.intVersion("android-targetSdk")
            }
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            lint {
                warningsAsErrors = true
                checkDependencies = true
                baseline = file("lint-baseline.xml")
            }
        }
        configureKotlinJvm()
        configureQuality()
    }
}
