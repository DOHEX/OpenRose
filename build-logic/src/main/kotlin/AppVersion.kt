import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * The application version. `version.txt` at the repository root holds the latest release as `MAJOR.MINOR.PATCH`;
 * release-please rewrites it in each release PR. Every form a platform needs is derived here, so nothing else in
 * the build holds a version. CI sets the Gradle property `releaseBuild` only for a release tag matching the file.
 */
class AppVersion internal constructor(major: Int, minor: Int, patch: Int, release: Boolean, commit: String?) {
    /** `MAJOR.MINOR.PATCH`, the only form every desktop package accepts (MSI allows no suffix). */
    val packageVersion: String = "$major.$minor.$patch"

    /** What users see: the release itself, or `-dev` and the commit for any other build. */
    val name: String = if (release) packageVersion else "$packageVersion-dev" + commit?.let { "+$it" }.orEmpty()

    /**
     * Android's versionCode, ordered like the versions it stands for. Android rejects 0, which only 0.0.0 maps to:
     * the placeholder before the first release.
     */
    val code: Int = (major * MAJOR_STEP + minor * MINOR_STEP + patch).coerceAtLeast(1)
}

private const val MAJOR_STEP = 1_000_000
private const val MINOR_STEP = 1_000

/** MSI caps major and minor at 255; [AppVersion.code] leaves three digits each to minor and patch. */
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

/** The checked-out commit, or null outside a git checkout (a source archive), where the version names no commit. */
private fun Project.currentCommit(): String? = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.ifEmpty { null }

/** Writes `internal object AppVersion { const val NAME }` for a module's common code. */
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
