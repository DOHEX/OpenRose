import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = libs.intVersion("android-compileSdk")
            buildToolsVersion = libs.stringVersion("android-buildTools")
            ndkVersion = libs.stringVersion("android-ndk")
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
                informational += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
            }
        }
        configureKotlinJvm()
        configureQuality()
    }
}
