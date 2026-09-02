plugins {
    `kotlin-dsl`
}

// Все Gradle-плагины подключаются здесь и только здесь. Модули применяют
// конвенции по имени, без версий — иначе Kotlin-плагин грузится дважды
// разными загрузчиками, о чём Gradle честно ругается.
dependencies {
    val kotlin = libs.versions.kotlin.get()
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlin")
    implementation("org.jetbrains.compose:compose-gradle-plugin:${libs.versions.composeMultiplatform.get()}")
}
