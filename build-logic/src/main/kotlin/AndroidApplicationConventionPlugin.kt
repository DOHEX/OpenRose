import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android application on the catalog SDK levels and the shared JDK; Kotlin is AGP's built-in support.
 * Its version comes from [appVersion]. Lint checks the application and its library dependencies and fails on warnings.
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
                val version = appVersion
                versionCode = version.code
                versionName = version.name
            }
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            lint {
                warningsAsErrors = true
                checkDependencies = true
                // Dependabot proposes upgrades. Publishing a newer version must not fail a pinned build.
                informational += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
            }
        }
        configureKotlinJvm()
        configureQuality()
    }
}
