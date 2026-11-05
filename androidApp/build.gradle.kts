plugins {
    alias(libs.plugins.openrose.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "io.github.dohex.openrose"
    defaultConfig {
        applicationId = "io.github.dohex.openrose"
        versionCode = providers.gradleProperty("releaseVersionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-dev")
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
dependencies {
    implementation(projects.androidIntegration)
    implementation(projects.androidIntegrationUI)
    implementation(projects.sharedUI)
    implementation(libs.activity.compose)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}
