plugins {
    id("eide.compose-multiplatform")
}

// KMP, а не обычный JVM-модуль: сюда позже добавится androidTarget(), и код
// в commonMain уедет на Android без переписывания.
kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // material3 намеренно не подключаем: UI-кит свой (ADR-001),
            // а material тянет мегабайты ради вида, который нам не нужен.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)

            implementation(project(":core"))
            implementation(project(":platform:api"))
        }
    }
}
