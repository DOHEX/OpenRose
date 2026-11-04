plugins {
    alias(libs.plugins.openrose.kmp.library)
}
kotlin {
    android {
        namespace = "io.github.dohex.openrose.core"
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.immutable.collections)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
