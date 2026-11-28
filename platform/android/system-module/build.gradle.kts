import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins {
    alias(libs.plugins.openrose.android.library)
    alias(libs.plugins.openrose.rust.ffi)
}
evaluationDependsOn(":platform:android:ffi")
android {
    namespace = "io.github.dohex.openrose.module"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}
val ffiProject = project(":platform:android:ffi")
val simulatorBindings = ffiProject.tasks.named<GenerateUniffiBindingsTask>("generateSimulatorBindings")
val simulatedDrivers = ffiProject.layout.projectDirectory.dir("src/jvmMain/kotlin")
extensions.configure<LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        variant.hostTests["UnitTest"]?.sources?.kotlin?.apply {
            addGeneratedSourceDirectory(simulatorBindings, GenerateUniffiBindingsTask::outputDir)
            addStaticSourceDirectory(simulatedDrivers.asFile.path)
        }
    }
}
dependencies {
    implementation(projects.platform.android.bluetoothIpc)
    implementation(projects.platform.android.ffi)
    implementation(libs.androidx.core)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.libxposed.api)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
