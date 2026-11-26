import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Project
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

fun Project.rustFfiSourceInputs(): ConfigurableFileCollection = files(
    rootProject.layout.projectDirectory.file("Cargo.toml"),
    rootProject.layout.projectDirectory.file("Cargo.lock"),
    fileTree(rootProject.layout.projectDirectory.dir("crates/headset")) { exclude("target/**") },
    fileTree(rootProject.layout.projectDirectory.dir("crates/control")) { exclude("target/**") },
    fileTree(rootProject.layout.projectDirectory.dir("crates/ffi")) { exclude("target/**") },
)

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

abstract class BuildAndroidRustFfiTask : DefaultTask() {
    @get:Internal
    abstract val workspaceRoot: DirectoryProperty

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val abi: Property<String>

    @get:Input
    abstract val platformLevel: Property<Int>

    @get:Input
    abstract val release: Property<Boolean>

    @get:Input
    abstract val offline: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun build() {
        val destination = outputDir.get().asFile
        fileSystemOperations.delete { delete(destination) }
        destination.mkdirs()
        val ndkDir = ndkDirectory.get().asFile
        val args = mutableListOf(
            "cargo",
            "ndk",
            "-t",
            abi.get(),
            "-P",
            platformLevel.get().toString(),
            "-o",
            destination.absolutePath,
            "build",
            "--locked",
            "-p",
            "openrose-ffi",
        )
        if (release.get()) args += "--release"
        if (offline.get()) args += "--offline"
        execOperations.exec {
            workingDir(workspaceRoot.get().asFile)
            environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
            commandLine(args)
        }
    }
}

fun Project.configureAndroidRustFfi(androidComponents: ApplicationAndroidComponentsExtension) {
    val sdkNdkDirectory = androidComponents.sdkComponents.ndkDirectory
    val pinnedNdkVersion = libs.stringVersion("android-ndk")
    val minSdkLevel = libs.intVersion("android-minSdk")
    androidComponents.onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val buildRustFfi = tasks.register("buildAndroidRustFfi$variantName", BuildAndroidRustFfiTask::class.java) {
            workspaceRoot.set(rootProject.layout.projectDirectory)
            ndkDirectory.set(sdkNdkDirectory)
            ndkVersion.set(pinnedNdkVersion)
            abi.set("arm64-v8a")
            platformLevel.set(minSdkLevel)
            release.set(variant.buildType == "release")
            offline.set(gradle.startParameter.isOffline)
            rustInputs.from(rustFfiSourceInputs())
            outputDir.set(layout.buildDirectory.dir("generated/rustJniLibs/${variant.name}"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustFfi, BuildAndroidRustFfiTask::outputDir)
    }
}
