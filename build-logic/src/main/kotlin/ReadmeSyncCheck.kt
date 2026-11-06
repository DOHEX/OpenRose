import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/** Registers `checkReadmeSync`: README.md is the primary text, README.en.md its translation. */
class ReadmeSyncConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        tasks.register<ReadmeSyncCheck>("checkReadmeSync") {
            group = "verification"
            description = "Check that README.en.md keeps the headings, code blocks, tables, images and links of README.md."
            primary.set(layout.projectDirectory.file("README.md"))
            translation.set(layout.projectDirectory.file("README.en.md"))
            result.set(layout.buildDirectory.file("readme-sync/result.txt"))
        }
    }
}

/**
 * Fails when two translations of the same README drift apart in shape: heading levels in order, code
 * blocks, table rows, images and link targets must match. Prose is free to differ; links between the
 * two translations are ignored.
 */
@CacheableTask
abstract class ReadmeSyncCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val primary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val translation: RegularFileProperty

    /** Marker that lets Gradle skip the check while both files are unchanged. */
    @get:OutputFile
    abstract val result: RegularFileProperty

    @TaskAction
    fun check() {
        val primaryFile = primary.get().asFile
        val translationFile = translation.get().asFile
        val names = setOf(primaryFile.name, translationFile.name)
        val expected = ReadmeShape.of(primaryFile.readText(), names)
        val actual = ReadmeShape.of(translationFile.readText(), names)
        val mismatches = ReadmeShape.differences(expected, actual)
        if (mismatches.isNotEmpty()) {
            throw GradleException(
                "${translationFile.name} is out of sync with ${primaryFile.name}:\n" +
                    mismatches.joinToString("\n") { "  - $it" },
            )
        }
        result.get().asFile.writeText("in sync\n")
    }
}

internal data class ReadmeShape(
    val headingLevels: List<Int>,
    val codeBlocks: Int,
    val tableRows: Int,
    val images: List<String>,
    val links: List<String>,
) {
    companion object {
        private val heading = Regex("^(#{1,6})\\s")
        private val image = Regex("!\\[[^\\]]*]\\(([^)\\s]+)")
        private val link = Regex("(?<!!)\\[[^\\]]*]\\(([^)\\s#]+)")

        fun of(markdown: String, translationNames: Set<String>): ReadmeShape {
            val headingLevels = mutableListOf<Int>()
            var codeBlocks = 0
            var tableRows = 0
            val images = mutableListOf<String>()
            val links = mutableListOf<String>()
            var inCode = false
            for (line in markdown.lineSequence()) {
                if (line.trimStart().startsWith("```")) {
                    if (!inCode) codeBlocks++
                    inCode = !inCode
                    continue
                }
                if (inCode) continue
                heading.find(line)?.let { headingLevels += it.groupValues[1].length }
                if (line.trimStart().startsWith("|")) tableRows++
                image.findAll(line).forEach { images += it.groupValues[1] }
                link.findAll(line).map { it.groupValues[1] }.filterNot { it in translationNames }.forEach { links += it }
            }
            return ReadmeShape(headingLevels, codeBlocks, tableRows, images, links.sorted())
        }

        fun differences(expected: ReadmeShape, actual: ReadmeShape): List<String> = buildList {
            if (expected.headingLevels != actual.headingLevels) {
                add("heading levels ${actual.headingLevels} differ from ${expected.headingLevels}")
            }
            if (expected.codeBlocks != actual.codeBlocks) add("${actual.codeBlocks} code blocks, expected ${expected.codeBlocks}")
            if (expected.tableRows != actual.tableRows) add("${actual.tableRows} table rows, expected ${expected.tableRows}")
            if (expected.images != actual.images) add("images ${actual.images} differ from ${expected.images}")
            if (expected.links != actual.links) {
                add("link targets differ; missing ${expected.links - actual.links.toSet()}, extra ${actual.links - expected.links.toSet()}")
            }
        }
    }
}
