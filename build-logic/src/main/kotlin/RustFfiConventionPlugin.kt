import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

private const val JVM_TEST_TASK = "jvmTest"

private class JnaLibraryPath(@get:Internal val directory: Provider<String>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Djna.library.path=${directory.get()}")
}

class RustFfiConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        tasks.withType<Test>().matching { it.name == JVM_TEST_TASK }.configureEach {
            val library = hostRustFfiLibrary()
            inputs.file(library).withPropertyName("hostRustFfiLibrary")
            jvmArgumentProviders.add(JnaLibraryPath(library.map { it.asFile.parentFile.absolutePath }))
        }
        pluginManager.withPlugin("com.android.application") {
            registerAndroidRustFfi(extensions.getByType<ApplicationAndroidComponentsExtension>())
        }
    }
}

private fun Project.registerAndroidRustFfi(androidComponents: ApplicationAndroidComponentsExtension) {
    val sdkNdkDirectory = androidComponents.sdkComponents.ndkDirectory
    val pinnedNdkVersion = libs.stringVersion("android-ndk")
    val minSdkLevel = libs.intVersion("android-minSdk")
    androidComponents.onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val buildRustFfi = registerRustFfiTask<BuildAndroidRustFfiTask>("buildAndroidRustFfi$variantName") {
            ndkDirectory.set(sdkNdkDirectory)
            ndkVersion.set(pinnedNdkVersion)
            abis.set(androidRustAbis)
            platformLevel.set(minSdkLevel)
            release.set(variant.buildType == "release")
            outputDir.set(layout.buildDirectory.dir("generated/rustJniLibs/${variant.name}"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustFfi, BuildAndroidRustFfiTask::outputDir)
    }
}
