// Конвенция для десктопного приложения: обычный JVM-модуль плюс Compose.

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
}
