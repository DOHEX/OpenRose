import org.gradle.api.Plugin
import org.gradle.api.Project

/** Kotlin/JVM library for desktop-only code such as platform transports. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()
        configureQuality()
    }
}
