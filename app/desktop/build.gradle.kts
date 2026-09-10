import org.gradle.api.tasks.PathSensitivity

plugins {
    id("eide.compose-jvm-app")
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":platform:api"))
    implementation(project(":platform:desktop"))
    implementation(project(":runner"))
    implementation(project(":vcs"))

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

    val library = rootProject.layout.buildDirectory
        .file("native-canvas/lib/libeide_canvas.so").get().asFile
    val shim = rootProject.layout.projectDirectory.dir("native/canvas/python").asFile

    // Библиотека и шим объявлены входами намеренно. Без этого Gradle считает
    // тест актуальным, пока не менялся Kotlin, — а он проверяет как раз стык
    // Kotlin с C. Правка в C давала зелёную сборку при сломанном стыке: так
    // в ветку уехало несовпадение версии области, и нашлось оно случайно.
    inputs.file(library).withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(shim).withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty("eide.canvasLib", library.absolutePath)
    systemProperty("eide.shimDir", shim.absolutePath)
}

/*
 * Нативная библиотека и шим на Python едут вместе с приложением.
 *
 * Пока их путь приходил только из свойств Gradle, собранный дистрибутив
 * запускал графику молча вникуда: библиотеки рядом нет, переменная окружения
 * пустая, программа пользователя падает на импорте. Проверить это можно было
 * только собрав дистрибутив, а собирать его в разработке незачем — классическое
 * «у меня работает» (риск R6).
 */
val canvasResourcesDir: Provider<Directory> = layout.buildDirectory.dir("canvas-resources")

val stageCanvasResources = tasks.register<Copy>("stageCanvasResources") {
    dependsOn(rootProject.tasks.named("canvasLibrary"))

    into(canvasResourcesDir)
    into("common") {
        from(rootProject.layout.buildDirectory.dir("native-canvas/lib")) {
            include("libeide_canvas.*", "eide_canvas.dll")
        }
        from(rootProject.layout.projectDirectory.dir("native/canvas/python")) {
            into("python")
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.effectnebula.eide.app.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(canvasResourcesDir)
        }
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
    System.getProperty("eide.fold")?.let { systemProperty("eide.fold", it) }
    System.getProperty("eide.drag")?.let { systemProperty("eide.drag", it) }

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

// Упаковка обязана дождаться копирования: appResourcesRootDir — это просто путь,
// зависимости от задачи в нём Gradle не видит и справедливо ругается.
tasks.matching { it.name == "prepareAppResources" }
    .configureEach { dependsOn(stageCanvasResources) }
