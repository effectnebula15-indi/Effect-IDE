plugins {
    id("eide.android-application")
}

android {
    namespace = "io.github.effectnebula.eide"

    defaultConfig {
        applicationId = "io.github.effectnebula.eide"
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runner:android"))
    implementation(project(":ui"))

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
