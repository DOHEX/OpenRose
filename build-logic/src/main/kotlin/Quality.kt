import org.gradle.api.Project

/** Product modules also instrument their JVM tests for the aggregate coverage report. */
internal fun Project.configureQuality() {
    pluginManager.apply(DetektConventionPlugin::class.java)
    pluginManager.apply("org.jetbrains.kotlinx.kover")
}
