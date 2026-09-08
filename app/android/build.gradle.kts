plugins {
    id("eide.android-application")
}

// Шрифты лежат в корне репозитория, а не в модуле: их два потребителя, и
// копия для каждого рассинхронизируется при первом же обновлении.
androidComponents.onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory(
        rootProject.layout.projectDirectory.dir("assets").asFile.absolutePath
    )
}

android {
    namespace = "io.github.effectnebula.eide"

    defaultConfig {
        applicationId = "io.github.effectnebula.eide"
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runner:android"))
    implementation(project(":ui"))
    implementation(project(":platform:android"))

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // Явно, а не транзитивно: от этой зависимости зависит сохранение текста
    // при уходе в фон, и молча потерять её при обновлении Compose нельзя.
    implementation(libs.androidx.lifecycle.runtime.compose)
}
