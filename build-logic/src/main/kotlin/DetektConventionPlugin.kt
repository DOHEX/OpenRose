import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Analyze all source sets, including tests, with the repository's rules and no local baseline. */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("dev.detekt")
        extensions.configure<DetektExtension> {
            baseline.unsetConvention()
            buildUponDefaultConfig.set(true)
            parallel.set(true)
            source.setFrom(layout.projectDirectory.dir("src"))
            config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
        }
    }
}
