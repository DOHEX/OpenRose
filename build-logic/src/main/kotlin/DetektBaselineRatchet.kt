import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

class DetektBaselineConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        tasks.register<DetektBaselineRatchet>("checkDetektBaseline") {
            group = "verification"
            description = "Reject detekt baseline IDs absent from the comparison commit."
            repository.set(layout.projectDirectory)
            revision.set(providers.gradleProperty("detektBaselineBase"))
            onlyIf("a Git checkout or explicit baseline revision is available") {
                repository.file(".git").get().asFile.exists() || revision.isPresent
            }
        }
    }
}

@DisableCachingByDefault(because = "The reference is resolved against Git history at execution time")
abstract class DetektBaselineRatchet : DefaultTask() {
    @get:Internal
    abstract val repository: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val revision: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun check() {
        val base = revision.orNull ?: localBase()
        val existing = git("ls-tree", "-r", "--name-only", base, "--", BASELINES).lineSequence().toSet()
        val added = mutableListOf<String>()
        val files = repository.dir(BASELINES).get().asFile.listFiles { file -> file.extension == "xml" }.orEmpty()
        for (file in files.sortedBy { it.name }) {
            val path = "$BASELINES/${file.name}"
            val previous = previousModuleNames[file.nameWithoutExtension]?.let { "$BASELINES/$it.xml" }
            val source = listOfNotNull(path, previous).firstOrNull { it in existing }
            val accepted = source?.let { ids(git("show", "$base:$it").byteInputStream()) }.orEmpty()
            val current = file.inputStream().use(::ids)
            added += (current - accepted).map { "${file.nameWithoutExtension}: $it" }
        }
        if (added.isNotEmpty()) {
            throw GradleException("Detekt findings newly suppressed since $base:\n${added.joinToString("\n")}")
        }
    }

    private fun localBase(): String {
        val result = ByteArrayOutputStream()
        val status = exec.exec {
            workingDir(repository.get().asFile)
            commandLine("git", "merge-base", "HEAD", "refs/remotes/origin/main")
            standardOutput = result
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return if (status.exitValue == 0) result.toString(Charsets.UTF_8).trim() else "HEAD"
    }

    private fun git(vararg args: String): String {
        val output = ByteArrayOutputStream()
        exec.exec {
            workingDir(repository.get().asFile)
            commandLine("git", *args)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8)
    }

    private fun ids(input: InputStream): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val entries = factory.newDocumentBuilder().parse(input).getElementsByTagName("ID")
        return (0 until entries.length).mapTo(mutableSetOf()) { entries.item(it).textContent }
    }

    private companion object {
        const val BASELINES = "config/detekt/baseline"

        // Comparisons can span the module reorganization. Carry over only the same module's findings.
        val previousModuleNames = mapOf(
            "platform-android-bluetooth-ipc" to "androidPlatform",
            "platform-android-integration" to "androidIntegration",
            "platform-android-system-module" to "androidModule",
            "platform-android-ui" to "androidIntegrationUI",
            "platform-linux" to "transportBluez",
            "shared-headset" to "core",
            "shared-ui" to "sharedUI",
        )
    }
}
