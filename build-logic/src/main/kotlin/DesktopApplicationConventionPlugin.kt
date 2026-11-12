import dev.nucleusframework.NucleusExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Kotlin/JVM Compose desktop entry point, run and packaged by Nucleus on the shared JDK, versioned by [appVersion]. */
class DesktopApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("dev.nucleusframework")
        configureKotlinJvm()
        configureQuality()
        extensions.configure<NucleusExtension> {
            // Nucleus otherwise runs and bundles whichever JVM started the Gradle daemon.
            application.javaHome = openRoseJdkHome().get()
            application.nativeDistributions.packageVersion = appVersion.packageVersion
        }
    }
}
