import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/** Registers `checkDetektBaseline`: the frozen detekt findings may only shrink. */
class DetektBaselineConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        tasks.register<DetektBaselineRatchet>("checkDetektBaseline") {
            group = "verification"
            description = "Check that config/detekt/baseline holds exactly the number of findings in config/detekt/baseline-limit.txt."
            baselines.set(layout.projectDirectory.dir("config/detekt/baseline"))
            limit.set(layout.projectDirectory.file("config/detekt/baseline-limit.txt"))
            result.set(layout.buildDirectory.file("detekt-baseline/result.txt"))
        }
    }
}

/**
 * Counts the findings frozen in the per-module baselines and compares them with the recorded limit.
 * More findings than the limit means new debt was added to a baseline; fewer means debt was paid and the
 * limit must follow, so the limit always equals the real count and never leaves room for new findings.
 * Once the baselines are empty, delete them, the limit file and this task: detekt then fails on any finding.
 */
@CacheableTask
abstract class DetektBaselineRatchet : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselines: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val limit: RegularFileProperty

    /** Marker that lets Gradle skip the check while the inputs are unchanged. */
    @get:OutputFile
    abstract val result: RegularFileProperty

    @TaskAction
    fun check() {
        val perModule = baselines.get().asFile.listFiles { file -> file.extension == "xml" }.orEmpty()
            .sortedBy { it.name }
            .associate { it.nameWithoutExtension to ENTRY.findAll(it.readText()).count() }
        val actual = perModule.values.sum()
        val limitFile = limit.get().asFile
        val recorded = limitFile.readText().trim().toIntOrNull()
            ?: throw GradleException("${limitFile.name} must contain a single integer.")
        val breakdown = perModule.entries.joinToString(", ") { "${it.key} ${it.value}" }
        when {
            actual > recorded -> throw GradleException(
                "The detekt baselines hold $actual findings, above the limit of $recorded ($breakdown). " +
                    "Fix the new findings instead of adding them to a baseline.",
            )

            actual < recorded -> throw GradleException(
                "The detekt baselines shrank to $actual findings. Set ${limitFile.name} to $actual so the gain stays ($breakdown).",
            )
        }
        result.get().asFile.writeText("$actual findings\n")
    }

    private companion object {
        val ENTRY = Regex("<ID>")
    }
}
