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
include(":platform:android")
include(":runner")

// Git в отдельном модуле, а не в :core (ADR-006): JGit не должен ехать
// в процесс-раннер, которому git не нужен.
include(":vcs")
include(":ui")
include(":app:desktop")

// Android появляется после десктопа: правило «сначала то, что видно сразу».
include(":runner:android")
include(":app:android")
