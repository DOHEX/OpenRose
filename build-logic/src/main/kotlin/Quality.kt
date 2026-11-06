import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Static analysis and coverage for every module: detekt over all of `src` (each source set, tests included)
 * against the shared config, with existing findings frozen in `config/detekt/baseline/<module>.xml`;
 * Kover instruments the module's JVM tests for the root coverage report.
 */
internal fun Project.configureQuality() {
    pluginManager.apply("dev.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        parallel.set(true)
        source.setFrom(layout.projectDirectory.dir("src"))
        config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
        baseline.set(rootDir.resolve("config/detekt/baseline/$name.xml"))
    }
    pluginManager.apply("org.jetbrains.kotlinx.kover")
}
