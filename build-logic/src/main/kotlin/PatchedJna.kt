import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

private val JNA_SOURCE_SHA256 = mapOf(
    "5.19.1" to "33a9e27b5a6eaa024bb117325392d1ccba7ca63f95d201230ad7c33d60f54c23",
)

private const val ANDROID_ARM64_ABI = "arm64-v8a"
private const val FFI_TARGET_ORIGINAL = "#elif !defined(_WIN32)"
private const val FFI_TARGET_PATCHED = "#elif !defined(_WIN32) && !defined(__ANDROID__)"
private const val COPT_FLAGS =
    "-O2 -fno-omit-frame-pointer -fno-strict-aliasing -Wno-implicit-function-declaration " +
        "-fpic -ffunction-sections -funwind-tables -fno-short-enums -ffixed-x18"
private const val LIBFFI_DEFINES =
    "-DFFI_STATIC_BUILD -DFFI_MMAP_EXEC_WRIT=1 -DFFI_MMAP_EXEC_SELINUX=0 -D_REENTRANT"
private const val JNA_DEFINES =
    "-DFFI_STATIC_BUILD -DNO_JAWT -DNO_WEAK_GLOBALS -DFFI_MMAP_EXEC_WRIT=1 -DFFI_MMAP_EXEC_SELINUX=0 -D_REENTRANT"
private val X18_REGISTER_PATTERN = Regex("\\b[xw]18\\b")

fun patchedJnaSourceSha256(version: String): String = JNA_SOURCE_SHA256[version]
    ?: error(
        "No pinned JNA source SHA-256 for $version. " +
            "Verify whether upstream JNA fixed Android arm64 x18 static trampolines before updating.",
    )

abstract class BuildPatchedJnaArm64Task : DefaultTask() {
    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val jnaVersion: Property<String>

    @get:Input
    abstract val jnaSourceSha256: Property<String>

    @get:Input
    abstract val platformLevel: Property<Int>

    @get:Internal
    abstract val offline: Property<Boolean>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun build() {
        val version = jnaVersion.get()
        val tarball = ensureSourceTarball(version, jnaSourceSha256.get())
        val work = workDir.get().asFile
        val out = outputDir.get().asFile
        fileSystemOperations.delete { delete(work, out) }
        work.mkdirs()

        val sourceDir = extractSource(tarball, work, version)
        patchFfiTarget(sourceDir)
        val metadata = readJnaMetadata(sourceDir)
        val headersDir = generateJniHeaders(sourceDir, work)
        val toolchainBin = resolveNdkToolchainBin(ndkDirectory.get().asFile)
        val libffiBuildDir = buildLibffi(sourceDir, work, toolchainBin, platformLevel.get())
        val builtSo = buildJniDispatch(
            sourceDir,
            work,
            headersDir,
            libffiBuildDir,
            toolchainBin,
            platformLevel.get(),
            metadata,
        )
        verifyBinary(toolchainBin, builtSo)

        val abiDir = File(out, ANDROID_ARM64_ABI).apply { mkdirs() }
        builtSo.copyTo(File(abiDir, "libjnidispatch.so"), overwrite = true)
    }

    private fun ensureSourceTarball(version: String, expectedSha256: String): File {
        val dir = cacheDir.get().asFile.apply { mkdirs() }
        val target = File(dir, "jna-$version.tar.gz")
        if (target.isFile && sha256Hex(target) == expectedSha256) return target
        if (offline.get()) {
            error("Offline build cannot download JNA $version source tarball; populate ${target.absolutePath}.")
        }
        val part = File(dir, "jna-$version.tar.gz.part")
        val url = URI("https://github.com/java-native-access/jna/archive/refs/tags/$version.tar.gz").toURL()
        url.openStream().use { input ->
            part.outputStream().use { output -> input.copyTo(output) }
        }
        val actualSha256 = sha256Hex(part)
        if (actualSha256 != expectedSha256) {
            part.delete()
            error("SHA-256 mismatch for $url: expected $expectedSha256, got $actualSha256.")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    private fun extractSource(tarball: File, work: File, version: String): File {
        runCommand(listOf("tar", "-xzf", tarball.absolutePath, "-C", work.absolutePath), work)
        val sourceDir = File(work, "jna-$version")
        check(sourceDir.isDirectory) { "Expected extracted JNA source directory at ${sourceDir.absolutePath}." }
        return sourceDir
    }

    private fun generateJniHeaders(sourceDir: File, work: File): File {
        val headersDir = File(work, "headers").apply { mkdirs() }
        val classesDir = File(work, "classes").apply { mkdirs() }
        val javac = File(System.getProperty("java.home"), "bin/javac").absolutePath
        val srcRoot = File(sourceDir, "src")
        runCommand(
            listOf(
                javac,
                "-Xlint:none",
                "-h",
                headersDir.absolutePath,
                "-d",
                classesDir.absolutePath,
                "-sourcepath",
                srcRoot.absolutePath,
                File(srcRoot, "com/sun/jna/Native.java").absolutePath,
                File(srcRoot, "com/sun/jna/Function.java").absolutePath,
            ),
            work,
        )
        return headersDir
    }

    private fun buildLibffi(sourceDir: File, work: File, toolchainBin: File, apiLevel: Int): File {
        val libffiSourceDir = File(sourceDir, "native/libffi")
        val configure = File(libffiSourceDir, "configure")
        if (!configure.isFile) {
            runCommand(listOf("sh", File(libffiSourceDir, "autogen.sh").absolutePath), libffiSourceDir)
        }
        val buildDir = File(work, "libffi").apply { mkdirs() }
        val cc = File(toolchainBin, "aarch64-linux-android$apiLevel-clang").absolutePath
        val cxx = File(toolchainBin, "aarch64-linux-android$apiLevel-clang++").absolutePath
        val ar = File(toolchainBin, "llvm-ar").absolutePath
        val ranlib = File(toolchainBin, "llvm-ranlib").absolutePath
        val env = mapOf("PATH" to "${toolchainBin.absolutePath}${File.pathSeparator}${System.getenv("PATH").orEmpty()}")

        runCommand(
            listOf(
                "sh",
                configure.absolutePath,
                "--host=aarch64-linux-android",
                "--enable-static",
                "--disable-shared",
                "--with-pic=yes",
                "--disable-exec-static-tramp",
                "CC=$cc",
                "CXX=$cxx",
                "AR=$ar",
                "RANLIB=$ranlib",
                "CFLAGS=$COPT_FLAGS -DFFI_STATIC_BUILD",
                "CPPFLAGS=$LIBFFI_DEFINES -Dmalloc_getpagesize='getpagesize()'",
            ),
            buildDir,
            env,
        )
        runCommand(listOf("make", "-j${Runtime.getRuntime().availableProcessors()}"), buildDir, env)
        return buildDir
    }

    @Suppress("LongParameterList")
    private fun buildJniDispatch(
        sourceDir: File,
        work: File,
        headersDir: File,
        libffiBuildDir: File,
        toolchainBin: File,
        apiLevel: Int,
        metadata: JnaNativeMetadata,
    ): File {
        val cc = File(toolchainBin, "aarch64-linux-android$apiLevel-clang").absolutePath
        val strip = File(toolchainBin, "llvm-strip").absolutePath
        val nativeDir = File(sourceDir, "native")
        val commonFlags = COPT_FLAGS.split(' ') + JNA_DEFINES.split(' ') + listOf(
            "-I${headersDir.absolutePath}",
            "-I${File(libffiBuildDir, "include").absolutePath}",
            "-I${nativeDir.absolutePath}",
            "-DJNA_JNI_VERSION=\"${metadata.jniVersion}\"",
            "-DCHECKSUM=\"${metadata.checksum}\"",
        )
        val dispatchObj = File(work, "dispatch.o")
        val callbackObj = File(work, "callback.o")
        val outputSo = File(work, "libjnidispatch.so")

        runCommand(
            listOf(cc) + commonFlags + listOf(
                "-c",
                File(nativeDir, "dispatch.c").absolutePath,
                "-o",
                dispatchObj.absolutePath,
            ),
            work,
        )
        runCommand(
            listOf(cc) + commonFlags + listOf(
                "-c",
                File(nativeDir, "callback.c").absolutePath,
                "-o",
                callbackObj.absolutePath,
            ),
            work,
        )
        runCommand(
            listOf(
                cc,
                "-shared",
                "-Wl,-z,max-page-size=16384",
                "-Wl,--gc-sections",
                "-o",
                outputSo.absolutePath,
                dispatchObj.absolutePath,
                callbackObj.absolutePath,
                File(libffiBuildDir, ".libs/libffi.a").absolutePath,
                "-ldl",
            ),
            work,
        )
        runCommand(listOf(strip, "-x", outputSo.absolutePath), work)
        return outputSo
    }

    private fun verifyBinary(toolchainBin: File, library: File) {
        val objdump = File(toolchainBin, "llvm-objdump").absolutePath
        val disasm = runCommandCapture(listOf(objdump, "-d", library.absolutePath), library.parentFile)
        val x18Lines = disasm.lineSequence().filter { X18_REGISTER_PATTERN.containsMatchIn(it) }.take(5).toList()
        check(x18Lines.isEmpty()) {
            "Patched ${library.name} still references x18/w18:\n${x18Lines.joinToString("\n")}"
        }

        val readelf = File(toolchainBin, "llvm-readelf").absolutePath
        val segments = runCommandCapture(listOf(readelf, "-l", library.absolutePath), library.parentFile)
        val loadLines = segments.lineSequence().map { it.trim() }.filter { it.startsWith("LOAD") }.toList()
        check(loadLines.isNotEmpty() && loadLines.all { it.endsWith("0x4000") }) {
            "Patched ${library.name} LOAD segments are not 16KB aligned:\n${loadLines.joinToString("\n")}"
        }
    }

    private fun runCommand(args: List<String>, workingDirectory: File, env: Map<String, String> = emptyMap()) {
        execOperations.exec {
            workingDir = workingDirectory
            environment(env)
            commandLine(args)
        }
    }

    private fun runCommandCapture(args: List<String>, workingDirectory: File): String {
        val stdout = ByteArrayOutputStream()
        execOperations.exec {
            workingDir = workingDirectory
            commandLine(args)
            standardOutput = stdout
        }
        return stdout.toString(Charsets.UTF_8)
    }
}

private fun patchFfiTarget(sourceDir: File) {
    val target = File(sourceDir, "native/libffi/src/aarch64/ffitarget.h")
    val lines = target.readLines()
    val matches = lines.count { it == FFI_TARGET_ORIGINAL }
    check(matches == 1) {
        "Expected exactly 1 '$FFI_TARGET_ORIGINAL' line in ${target.absolutePath}, found $matches. " +
            "Check whether upstream JNA fixed Android arm64 x18 static trampolines."
    }
    val patched = lines.joinToString(separator = "\n", postfix = "\n") { line ->
        if (line == FFI_TARGET_ORIGINAL) FFI_TARGET_PATCHED else line
    }
    target.writeText(patched)
}

private fun readJnaMetadata(sourceDir: File): JnaNativeMetadata {
    val xml = File(sourceDir, "common.xml").readText()
    fun prop(name: String): String {
        val regex = Regex("""<property\s+name="${Regex.escape(name)}"\s+value="([^"]+)"\s*/>""")
        return regex.find(xml)?.groupValues?.get(1)
            ?: error("Missing property '$name' in ${sourceDir.absolutePath}/common.xml.")
    }
    val jniVersion = "${prop("jni.major")}.${prop("jni.minor")}.${prop("jni.revision")}"
    return JnaNativeMetadata(jniVersion = jniVersion, checksum = prop("jni.md5"))
}

private fun resolveNdkToolchainBin(ndkDir: File): File {
    val prebuiltDir = File(ndkDir, "toolchains/llvm/prebuilt")
    val hostDir = prebuiltDir.listFiles()?.firstOrNull { it.isDirectory && File(it, "bin").isDirectory }
        ?: error("Unable to locate NDK LLVM prebuilt toolchain under ${prebuiltDir.absolutePath}.")
    return File(hostDir, "bin")
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private data class JnaNativeMetadata(
    val jniVersion: String,
    val checksum: String,
)
