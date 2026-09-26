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

// Сверка с Android API 27 классов Android-цели: общий код этого модуля едет на
// телефон целиком, а lint его на API новее minSdk не проверяет (AndroidApiCheck.kt).
registerAndroidApiCheck(
    classesDir = "classes/kotlin/android/main",
    compileTask = "compileAndroidMain",
    classpathConfiguration = "androidCompileClasspath",
    sourceDirs = listOf("src/commonMain/kotlin", "src/androidMain/kotlin"),
)
