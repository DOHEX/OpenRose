import org.gradle.api.Project

internal fun Project.configureQuality() {
    pluginManager.apply(DetektConventionPlugin::class.java)
    pluginManager.apply(KoverConventionPlugin::class.java)
}
