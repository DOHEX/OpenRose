import org.gradle.api.Plugin
import org.gradle.api.Project

/** [KmpLibraryConventionPlugin] with Compose Multiplatform and its compiler. */
class KmpComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("openrose.kmp.library")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    }
}
