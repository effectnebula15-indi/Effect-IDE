plugins {
    id("eide.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    // JGit, а не вызов системного git: на Android git-бинарника нет, а положить
    // свой мешает запрет на исполнение файлов из каталога данных (ADR-006).
    api(libs.jgit)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
