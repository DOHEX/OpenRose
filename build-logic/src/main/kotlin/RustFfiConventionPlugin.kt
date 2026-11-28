import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

private const val JVM_TEST_TASK = "jvmTest"

private class JnaLibraryPath(@get:Internal val directory: Provider<String>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Djna.library.path=${directory.get()}")
}

class RustFfiConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        tasks.withType<Test>().matching { it.name == JVM_TEST_TASK || it.name.endsWith("UnitTest") }.configureEach {
            val library = hostRustFfiLibrary()
            inputs.file(library).withPropertyName("hostRustFfiLibrary")
            jvmArgumentProviders.add(JnaLibraryPath(library.map { it.asFile.parentFile.absolutePath }))
        }
        pluginManager.withPlugin("com.android.application") {
            val shippedAbis = libs.stringVersion("android-abis").split(' ')
            extensions.configure<ApplicationExtension> { defaultConfig.ndk.abiFilters += shippedAbis }
            registerAndroidRustFfi(extensions.getByType<ApplicationAndroidComponentsExtension>(), shippedAbis)
        }
    }
}

private fun Project.registerAndroidRustFfi(
    androidComponents: ApplicationAndroidComponentsExtension,
    shippedAbis: List<String>,
) {
    val sdkNdkDirectory = androidComponents.sdkComponents.ndkDirectory
    val pinnedNdkVersion = libs.stringVersion("android-ndk")
    val minSdkLevel = libs.intVersion("android-minSdk")
    val jnaVersion = libs.stringVersion("jna")
    val jnaSha256 = patchedJnaSourceSha256(jnaVersion)
    val jnaCacheDir = gradle.gradleUserHomeDir.resolve("caches/openrose-jna")
    val buildPatchedJna = "arm64-v8a" in shippedAbis
    androidComponents.onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val buildRustFfi = registerRustFfiTask<BuildAndroidRustFfiTask>("buildAndroidRustFfi$variantName") {
            ndkDirectory.set(sdkNdkDirectory)
            ndkVersion.set(pinnedNdkVersion)
            abis.set(shippedAbis)
            platformLevel.set(minSdkLevel)
            release.set(variant.buildType == "release")
            outputDir.set(layout.buildDirectory.dir("generated/rustJniLibs/${variant.name}"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustFfi, BuildAndroidRustFfiTask::outputDir)
        if (buildPatchedJna) {
            val patchedJna = tasks.register("buildPatchedJnaArm64$variantName", BuildPatchedJnaArm64Task::class.java) {
                ndkDirectory.set(sdkNdkDirectory)
                ndkVersion.set(pinnedNdkVersion)
                this.jnaVersion.set(jnaVersion)
                jnaSourceSha256.set(jnaSha256)
                platformLevel.set(minSdkLevel)
                offline.set(gradle.startParameter.isOffline)
                cacheDir.set(jnaCacheDir)
                workDir.set(layout.buildDirectory.dir("intermediates/patchedJnaArm64/${variant.name}"))
                outputDir.set(layout.buildDirectory.dir("generated/patchedJnaJniLibs/${variant.name}"))
            }
            variant.sources.jniLibs?.addGeneratedSourceDirectory(patchedJna, BuildPatchedJnaArm64Task::outputDir)
        }
    }
}
