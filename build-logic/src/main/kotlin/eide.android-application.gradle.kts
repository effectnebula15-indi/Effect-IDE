// Конвенция для Android-приложения.
//
// Kotlin встроен в AGP 9; отдельно подключаем только компилятор Compose.
// Плагин org.jetbrains.compose не нужен: зависимости берём явно из каталога.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileSdk = 37
    compileSdkMinor = 2
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 27
        targetSdk = 36

        // arm64 и только он: второй ABI удваивает вес нативной части,
        // а бюджет APK — 60 МБ (docs/plan.md).
        ndk.abiFilters.add("arm64-v8a")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
