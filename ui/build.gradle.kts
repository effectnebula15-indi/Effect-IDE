plugins {
    id("eide.compose-multiplatform")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// KMP, а не обычный JVM-модуль: сюда позже добавится androidTarget(), и код
// в commonMain уедет на Android без переписывания.
kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.effectnebula.eide.ui"
        compileSdk = 37
        minSdk = 27
    }

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)

            // Проверка самого интерфейса: жесты, попадание, отрисовка. Единственный
            // способ увидеть поведение UI, не имея телефона и не нажимая руками.
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }

        commonMain.dependencies {
            // material3 намеренно не подключаем: UI-кит свой (ADR-001),
            // а material тянет мегабайты ради вида, который нам не нужен.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)

            implementation(project(":core"))
            implementation(project(":platform:api"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
