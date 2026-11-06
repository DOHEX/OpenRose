import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** The JDK every module compiles and packages with; JVM and Android bytecode targets the same level. */
private const val JAVA_RELEASE = 21

internal val javaVersion: JavaVersion = JavaVersion.toVersion(JAVA_RELEASE)

/** Temurin, because desktop packaging bundles this JDK and needs its `jpackage`, which IDE runtimes lack. */
private fun JavaToolchainSpec.openRoseJdk() {
    languageVersion.set(JavaLanguageVersion.of(JAVA_RELEASE))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.intVersion(alias: String): Int = findVersion(alias).orElseThrow { IllegalStateException("Version '$alias' is missing from the catalog") }
    .requiredVersion.toInt()

internal fun VersionCatalog.stringVersion(alias: String): String = findVersion(alias).orElseThrow { IllegalStateException("Version '$alias' is missing from the catalog") }
    .requiredVersion

/**
 * Compiles every Kotlin JVM and Android compilation with the shared JDK and bytecode target, and fails every
 * Kotlin compilation, common metadata included, on any compiler warning.
 */
internal fun Project.configureKotlinJvm() {
    kotlinExtension.jvmToolchain { openRoseJdk() }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

/** Installation directory of the shared JDK, independent of the JVM running Gradle. */
internal fun Project.openRoseJdkHome(): Provider<String> = extensions.getByType<JavaToolchainService>().launcherFor { openRoseJdk() }
    .map { it.metadata.installationPath.asFile.absolutePath }
