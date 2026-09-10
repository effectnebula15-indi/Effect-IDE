// Конвенция для модулей общего UI: Kotlin Multiplatform + Compose.
//
// Android-таргет подключается плагином com.android.kotlin.multiplatform.library:
// с AGP 9 это штатный способ дать KMP-модулю Android, а не com.android.library.

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
}
