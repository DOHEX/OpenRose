plugins {
    alias(libs.plugins.openrose.kmp.library)
}
kotlin {
    android {
        namespace = "io.github.dohex.openrose.core"
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.datetime)
            api(libs.coroutines.core)
            api(libs.immutable.collections)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
kover {
    reports {
        verify { rule { minBound(93) } }
    }
}
