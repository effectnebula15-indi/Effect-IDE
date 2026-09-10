plugins {
    id("eide.android-library")
}

android {
    namespace = "io.github.effectnebula.eide.platform.android"
}

dependencies {
    implementation(project(":platform:api"))
}
