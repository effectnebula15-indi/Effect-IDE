plugins {
    id("eide.compose-jvm-app")
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":platform:api"))
    implementation(project(":platform:desktop"))
    implementation(project(":runner"))

    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "io.github.effectnebula.eide.app.MainKt"
    }
}

// Пробрасываем свойство замера в JVM приложения: -Deide.benchmarkSeconds=15
// на командной строке Gradle иначе достанется самому Gradle, а не программе.
// Шрифты лежат в корне репозитория, а не в модуле: их два потребителя, и
// копия для каждого рассинхронизируется при первом же обновлении.
sourceSets.named("main") {
    resources.srcDir(rootProject.layout.projectDirectory.dir("assets"))
}

tasks.withType<JavaExec>().configureEach {
    // Рабочий каталог — корень репозитория, а не каталог модуля: проектом
    // десктопная сборка считает то, откуда её запустили, и по умолчанию это
    // должен быть весь репозиторий, а не app/desktop.
    workingDir = rootProject.projectDir

    System.getProperty("eide.benchmarkSeconds")?.let {
        systemProperty("eide.benchmarkSeconds", it)
    }
    // На машине без GPU (например, под Xvfb) Skiko не поднимет GL-контекст.
    System.getProperty("skiko.renderApi")?.let { systemProperty("skiko.renderApi", it) }
    System.getProperty("eide.screenshot")?.let { systemProperty("eide.screenshot", it) }
    System.getProperty("eide.project")?.let { systemProperty("eide.project", it) }
    System.getProperty("eide.open")?.let { systemProperty("eide.open", it) }
    System.getProperty("eide.run")?.let { systemProperty("eide.run", it) }
    // Иначе вывод замера приезжает вопросительными знаками там, где кириллица.
    systemProperty("file.encoding", "UTF-8")
    systemProperty("stdout.encoding", "UTF-8")
}
