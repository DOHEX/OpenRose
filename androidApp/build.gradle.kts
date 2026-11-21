plugins {
    alias(libs.plugins.openrose.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "io.github.dohex.openrose"
    defaultConfig {
        applicationId = "io.github.dohex.openrose"
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        getByName("release") {
            // Shrinks code and resources; names stay readable (proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    providers.environmentVariable("OPENROSE_KEYSTORE_PATH").orNull?.let { keystorePath ->
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("OPENROSE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("OPENROSE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("OPENROSE_KEY_PASSWORD").get()
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(projects.platform.android.integration)
    implementation(projects.platform.android.ui)
    implementation(projects.shared.ui)
    implementation(libs.activity.compose)
    implementation(libs.zxing.embedded)
    implementation(projects.platform.android.bluetoothIpc)
    // Packaged into the APK for LSPosed, but invisible to the application's sources (ADR 0018).
    runtimeOnly(projects.platform.android.systemModule)
    // LSPosed supplies the API at runtime; R8 needs it as library classes to keep the module entry's overrides.
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
