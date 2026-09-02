// Общая настройка для всех JVM-модулей проекта.
//
// Важное следствие Шага 3: этот же байткод исполняется и на Android, поэтому цель
// компиляции держим на 17 и запрещаем себе API новее — иначе ошибка вылезет не на
// сборке, а на устройстве. Собираем при этом на JDK 21: он есть и в Android Studio,
// и на CI-раннерах.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Не даёт случайно позвать метод, которого нет в Java 17 (а значит и на Android).
        freeCompilerArgs.add("-Xjdk-release=17")
        extraWarnings.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
