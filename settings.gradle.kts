pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "effect-ide"

// Сокращённый набор модулей (решение Шага 3). Границы внутри :core держатся
// пакетами и задачей checkArchitecture, а не дроблением на Gradle-модули.
include(":core")
include(":platform:api")
include(":platform:desktop")
include(":runner")
include(":ui")
include(":app:desktop")
