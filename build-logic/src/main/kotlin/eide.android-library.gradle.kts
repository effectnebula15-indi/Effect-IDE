// Конвенция для Android-библиотек.
//
// Плагин org.jetbrains.kotlin.android здесь не применяется: с AGP 9 поддержка
// Kotlin встроена в сам AGP и отдельный плагин объявлен лишним.

plugins {
    id("com.android.library")
}

android {
    compileSdk = 37
    compileSdkMinor = 2
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 27 // android.os.SharedMemory (ADR-004)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Сверка с Android API 27: lint такой ошибки не ловит, проверено опытом
// (AndroidApiCheck.kt). Классы — отладочной сборки: код тот же, что в выпуске.
afterEvaluate {
    registerAndroidApiCheck(
        classesDir = "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
        compileTask = "compileDebugKotlin",
        classpathConfiguration = "debugCompileClasspath",
        sourceDirs = listOf("src/main/kotlin"),
    )
}
