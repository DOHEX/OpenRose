plugins {
    alias(libs.plugins.openrose.kmp.library)
}
kotlin {
    android {
        namespace = "io.github.dohex.openrose.circulation.contract"
    }
    sourceSets {
        commonMain.dependencies { api(projects.shared.headset) }
    }
}
