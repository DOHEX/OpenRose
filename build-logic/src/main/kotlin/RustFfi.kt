import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import javax.inject.Inject

private const val HOST_RUST_FFI_TASK = "buildHostRustFfi"

abstract class RustFfiCargoTask : DefaultTask() {
    @get:Internal
    abstract val workspaceRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @get:Input
    abstract val offline: Property<Boolean>

    @get:Inject
    abstract val execOperations: ExecOperations

    protected fun cargo(
        subcommand: List<String>,
        flags: List<String> = emptyList(),
        programArguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
    ) {
        val offlineFlags = if (offline.get()) listOf("--offline") else emptyList()
        val command = listOf("cargo") + subcommand + listOf("--locked", "-p", "openrose-ffi") + flags + offlineFlags + programArguments
        execOperations.exec {
            workingDir(workspaceRoot.get().asFile)
            environment(environment)
            commandLine(command)
        }
    }
}

abstract class BuildHostRustFfiTask : RustFfiCargoTask() {
    @get:Input
    abstract val features: ListProperty<String>

    @get:OutputFile
    abstract val outputLibrary: RegularFileProperty

    @TaskAction
    fun build() {
        val featureFlags = if (features.get().isEmpty()) emptyList() else listOf("--features", features.get().joinToString(","))
        cargo(listOf("build"), featureFlags)
    }
}

abstract class GenerateUniffiBindingsTask : RustFfiCargoTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val uniffiConfigs: ConfigurableFileCollection

    @get:Input
    abstract val crates: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun generate() {
        val destination = outputDir.get().asFile
        fileSystemOperations.delete { delete(destination) }
        destination.mkdirs()
        crates.get().forEach { crate ->
            cargo(
                subcommand = listOf("run"),
                flags = listOf("--features", "bindgen", "--bin", "uniffi-bindgen"),
                programArguments = listOf(
                    "--",
                    "generate",
                    "--library",
                    libraryFile.get().asFile.absolutePath,
                    "--crate",
                    crate,
                    "--language",
                    "kotlin",
                    "--no-format",
                    "--out-dir",
                    destination.absolutePath,
                ),
            )
        }
    }
}

abstract class BuildAndroidRustFfiTask : RustFfiCargoTask() {
    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val platformLevel: Property<Int>

    @get:Input
    abstract val release: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun build() {
        val destination = outputDir.get().asFile
        fileSystemOperations.delete { delete(destination) }
        destination.mkdirs()
        val targets = abis.get().flatMap { listOf("-t", it) }
        cargo(
            subcommand = listOf("ndk") + targets + listOf("-P", platformLevel.get().toString(), "-o", destination.absolutePath, "build"),
            flags = if (release.get()) listOf("--release") else emptyList(),
            environment = mapOf("ANDROID_NDK_HOME" to ndkDirectory.get().asFile.absolutePath),
        )
    }
}

inline fun <reified T : RustFfiCargoTask> Project.registerRustFfiTask(
    name: String,
    crossinline configure: T.() -> Unit,
): TaskProvider<T> = tasks.register<T>(name) {
    workspaceRoot.set(rootProject.layout.projectDirectory)
    offline.set(gradle.startParameter.isOffline)
    rustInputs.from(rustFfiSourceInputs())
    configure()
}

fun Project.rustFfiSourceInputs(): ConfigurableFileCollection = files(
    rootProject.layout.projectDirectory.file("Cargo.toml"),
    rootProject.layout.projectDirectory.file("Cargo.lock"),
    fileTree(rootProject.layout.projectDirectory.dir("crates/headset")) { exclude("target/**") },
    fileTree(rootProject.layout.projectDirectory.dir("crates/control")) { exclude("target/**") },
    fileTree(rootProject.layout.projectDirectory.dir("crates/ffi")) { exclude("target/**") },
)

fun Project.registerHostRustFfi(): TaskProvider<BuildHostRustFfiTask> = registerRustFfiTask(HOST_RUST_FFI_TASK) {
    features.add("simulator")
    rustInputs.from(fileTree(rootProject.layout.projectDirectory.dir("crates/ffi-simulator")))
    outputLibrary.set(rootProject.layout.projectDirectory.file("target/debug/${hostRustLibraryFileName()}"))
}

fun Project.hostRustFfiLibrary(): Provider<RegularFile> {
    val buildHost = rootProject.tasks.named<BuildHostRustFfiTask>(HOST_RUST_FFI_TASK)
    return buildHost.flatMap { it.outputLibrary }
}

private fun hostRustLibraryFileName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> "openrose_ffi.dll"
        os.contains("mac") -> "libopenrose_ffi.dylib"
        else -> "libopenrose_ffi.so"
    }
}
