import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

fun hostRustLibraryFileName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> "openrose_ffi.dll"
        os.contains("mac") -> "libopenrose_ffi.dylib"
        else -> "libopenrose_ffi.so"
    }
}

abstract class BuildHostRustFfiTask : DefaultTask() {
    @get:Internal
    abstract val workspaceRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @get:Input
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val outputLibrary: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val args = mutableListOf("cargo", "build", "--locked", "-p", "openrose-ffi")
        if (offline.get()) args += "--offline"
        execOperations.exec {
            workingDir(workspaceRoot.get().asFile)
            commandLine(args)
        }
    }
}

abstract class GenerateUniffiBindingsTask : DefaultTask() {
    @get:Internal
    abstract val workspaceRoot: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val uniffiConfigs: ConfigurableFileCollection

    @get:Input
    abstract val offline: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun generate() {
        val destination = outputDir.get().asFile
        fileSystemOperations.delete { delete(destination) }
        destination.mkdirs()
        val args = mutableListOf(
            "cargo",
            "run",
            "--locked",
            "-p",
            "openrose-ffi",
            "--features",
            "bindgen",
            "--bin",
            "uniffi-bindgen",
        )
        if (offline.get()) args += "--offline"
        args += listOf(
            "--",
            "generate",
            "--library",
            libraryFile.get().asFile.absolutePath,
            "--language",
            "kotlin",
            "--no-format",
            "--out-dir",
            destination.absolutePath,
        )
        execOperations.exec {
            workingDir(workspaceRoot.get().asFile)
            commandLine(args)
        }
    }
}
