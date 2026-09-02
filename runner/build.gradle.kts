plugins {
    id("eide.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform:api"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
