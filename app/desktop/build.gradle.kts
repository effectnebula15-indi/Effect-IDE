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

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Сквозная проверка графики живёт здесь, потому что ей нужны сразу и
// :platform:desktop (область кадров), и :runner (запуск Python). Границы
// модулей это запрещают всем, кроме точки сборки — и это как раз её работа.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("canvasLibrary"))

    systemProperty(
        "eide.canvasLib",
        rootProject.layout.buildDirectory.file("native-canvas/lib/libeide_canvas.so")
            .get().asFile.absolutePath,
    )
    systemProperty(
        "eide.shimDir",
        rootProject.layout.projectDirectory.dir("native/canvas/python").asFile.absolutePath,
    )
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
    System.getProperty("eide.benchmarkFont")?.let { systemProperty("eide.benchmarkFont", it) }
    System.getProperty("eide.benchmarkHighlight")?.let { systemProperty("eide.benchmarkHighlight", it) }
    // На машине без GPU (например, под Xvfb) Skiko не поднимет GL-контекст.
    System.getProperty("skiko.renderApi")?.let { systemProperty("skiko.renderApi", it) }
    System.getProperty("eide.screenshot")?.let { systemProperty("eide.screenshot", it) }
    System.getProperty("eide.project")?.let { systemProperty("eide.project", it) }
    System.getProperty("eide.open")?.let { systemProperty("eide.open", it) }
    System.getProperty("eide.run")?.let { systemProperty("eide.run", it) }
    System.getProperty("eide.canvas")?.let { systemProperty("eide.canvas", it) }
    System.getProperty("eide.search")?.let { systemProperty("eide.search", it) }

    // Пути к нативной библиотеке и шиму — те же, что у тестов. В собранном
    // дистрибутиве они поедут вместе с приложением; до упаковки графика на
    // десктопе работает при запуске из исходников.
    dependsOn(rootProject.tasks.named("canvasLibrary"))
    systemProperty(
        "eide.canvasLib",
        rootProject.layout.buildDirectory.file("native-canvas/lib/libeide_canvas.so")
            .get().asFile.absolutePath,
    )
    systemProperty(
        "eide.shimDir",
        rootProject.layout.projectDirectory.dir("native/canvas/python").asFile.absolutePath,
    )
    // Иначе вывод замера приезжает вопросительными знаками там, где кириллица.
    systemProperty("file.encoding", "UTF-8")
    systemProperty("stdout.encoding", "UTF-8")
}
