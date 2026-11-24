import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

private const val RULES_PROJECT = ":tools:detekt-rules"

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
        if (path != RULES_PROJECT) {
            dependencies.add("detektPlugins", dependencies.project(mapOf("path" to RULES_PROJECT)))
        }
        tasks.withType<Detekt>().configureEach {
            inputs.file(rootDir.resolve("config/detekt/comment-budget.txt")).withPropertyName("commentBudget")
        }
    }
}
