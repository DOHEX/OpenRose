import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Kotlin Multiplatform library with JVM and Android targets; modules add only their namespace. */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        extensions.configure<KotlinMultiplatformExtension> {
            jvm()
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryExtension> {
                compileSdk = libs.intVersion("android-compileSdk")
                minSdk = libs.intVersion("android-minSdk")
            }
        }
        configureKotlinJvm()
    }
}
