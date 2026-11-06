plugins {
    alias(libs.plugins.openrose.kmp.library)
}
kotlin {
    android {
        namespace = "io.github.dohex.openrose.androidintegration"
    }
    sourceSets {
        commonMain.dependencies { api(projects.core) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
// Android integration logic runs on the JVM and stays well covered (91.7% of lines when set).
kover {
    reports {
        verify { rule { minBound(90) } }
    }
}
