import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

private const val COVERAGE_VARIANT = "coverage"

class KoverConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        extensions.configure<KoverProjectExtension> {
            currentProject {
                createVariant(COVERAGE_VARIANT) { add("jvm", "android", "debug", optional = true) }
            }
            reports {
                total { verify { onCheck.set(false) } }
                variant(COVERAGE_VARIANT) { verify { onCheck.set(true) } }
            }
        }
    }
}
