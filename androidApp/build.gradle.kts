plugins {
    alias(libs.plugins.openrose.android.application)
    alias(libs.plugins.openrose.rust.ffi)
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
    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += "lib/arm64-v8a/libjnidispatch.so"
        }
    }
    buildTypes {
        getByName("release") {
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
    implementation(projects.platform.android.ffi)
    runtimeOnly(projects.platform.android.systemModule)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
