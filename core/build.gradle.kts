plugins {
    id("eide.kotlin-jvm")
}

dependencies {
    // Границы графем зависят от платформы: движение курсора не может быть
    // посимвольным по code unit'ам (ADR-005).
    api(project(":platform:api"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
