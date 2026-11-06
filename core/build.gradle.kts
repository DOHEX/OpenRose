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
// The shared control layer is the most tested code; keep it there (94.4% of lines when set).
kover {
    reports {
        verify { rule { minBound(93) } }
    }
}
