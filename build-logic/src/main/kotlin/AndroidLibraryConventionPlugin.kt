import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

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
