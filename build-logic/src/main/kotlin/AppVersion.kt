import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

class AppVersion internal constructor(major: Int, minor: Int, patch: Int, release: Boolean, commit: String?) {
    val packageVersion: String = "$major.$minor.$patch"

    val name: String = if (release) packageVersion else "$packageVersion-dev" + commit?.let { "+$it" }.orEmpty()

    val code: Int = (major * MAJOR_STEP + minor * MINOR_STEP + patch).coerceAtLeast(1)
}

private const val MAJOR_STEP = 1_000_000
private const val MINOR_STEP = 1_000

private const val MAX_MAJOR_OR_MINOR = 255
private const val MAX_PATCH = MINOR_STEP - 1

private val semanticVersion = Regex("""(\d+)\.(\d+)\.(\d+)""")

val Project.appVersion: AppVersion
    get() {
        val text = providers.fileContents(isolated.rootProject.projectDirectory.file("version.txt")).asText.get().trim()
        val parts = semanticVersion.matchEntire(text)?.groupValues?.drop(1)?.map(String::toInt)
            ?: error("version.txt must hold MAJOR.MINOR.PATCH, found '$text'")
        val (major, minor, patch) = parts
        check(major <= MAX_MAJOR_OR_MINOR && minor <= MAX_MAJOR_OR_MINOR && patch <= MAX_PATCH) {
            "version.txt '$text' is out of range: major and minor at most $MAX_MAJOR_OR_MINOR, patch at most $MAX_PATCH"
        }
        val release = providers.gradleProperty("releaseBuild").map(String::toBoolean).getOrElse(false)
        return AppVersion(major, minor, patch, release, if (release) null else currentCommit())
    }

private fun Project.currentCommit(): String? = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.ifEmpty { null }

@CacheableTask
abstract class GenerateAppVersionSource : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val file = root.resolve(packageName.get().replace('.', '/')).resolve("AppVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package ${packageName.get()}
            |
            |/** Generated from version.txt by build-logic's AppVersion; do not edit. */
            |internal object AppVersion {
            |    const val NAME: String = "${versionName.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}
