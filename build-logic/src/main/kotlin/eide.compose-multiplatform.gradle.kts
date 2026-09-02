// Конвенция для модулей общего UI: Kotlin Multiplatform + Compose.
//
// KMP, а не обычный JVM-модуль, потому что сюда позже добавится androidTarget(),
// и код из commonMain уедет на Android без переписывания (Шаг 3).

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
}
