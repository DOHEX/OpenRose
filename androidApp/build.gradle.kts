import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "io.github.dohex.openrose"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.dohex.openrose"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-dev")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
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
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
dependencies {
    implementation(project(":composeApp"))
    implementation(libs.activity.compose)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(kotlin("test-junit"))
}
