// Корневой билд. Здесь нет кода приложения — только правила, общие для всего репозитория.

tasks.register("checkArchitecture") {
    group = "verification"
    description = "Проверяет правила зависимостей между модулями (Шаг 3 плана)."

    // Разрешённые внутренние зависимости. Всё, чего нет в списке, запрещено.
    //
    // Смысл правил (docs/adr/ADR-003 и Шаг 3):
    //   core        — чистая логика, знает только platform:api. Тестируется без эмулятора.
    //   platform:api— интерфейсы того, что различается между Android и Desktop. Ни от чего не зависит.
    //   runner      — отдельный процесс. Про UI не знает вообще, связь только по протоколу.
    //   ui          — знает ядро и интерфейсы, но не конкретные платформенные реализации.
    //   app:*       — точка сборки, ей можно всё.
    val allowed: Map<String, Set<String>> = mapOf(
        ":core" to setOf(":platform:api"),
        ":platform:api" to emptySet(),
        ":platform:desktop" to setOf(":platform:api"),
        ":platform:android" to setOf(":platform:api"),
        ":runner" to setOf(":core", ":platform:api"),
        ":vcs" to setOf(":core"),
        ":runner:android" to setOf(":core", ":platform:api"),
        ":ui" to setOf(":core", ":platform:api"),
        ":app:desktop" to setOf(
            ":core", ":platform:api", ":platform:desktop", ":ui", ":runner", ":vcs",
        ),
        ":app:android" to setOf(
            ":core", ":platform:api", ":platform:android", ":ui", ":runner", ":runner:android",
            ":vcs",
        ),
    )

    // Проверка текстовая: ищем project(":...") в build-файлах модулей.
    // Это грубее семантического обхода конфигураций Gradle, зато совместимо
    // с configuration cache и читается без знания внутренностей Gradle.
    // Ограничение честное: зависимость, добавленную не литералом, задача не увидит.
    val buildFiles = allowed.keys.associateWith { path ->
        layout.projectDirectory.file(path.removePrefix(":").replace(':', '/') + "/build.gradle.kts").asFile
    }
    inputs.files(buildFiles.values.filter { it.exists() })

    doLast {
        val referenced = Regex("""project\("(:[A-Za-z0-9:_-]+)"\)""")
        val violations = mutableListOf<String>()

        buildFiles.forEach { (module, file) ->
            if (!file.exists()) return@forEach
            val permitted = allowed.getValue(module)
            referenced.findAll(file.readText())
                .map { it.groupValues[1] }
                .filter { it != module }
                .distinct()
                .filterNot { it in permitted }
                .forEach { violations += "$module не имеет права зависеть от $it" }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Нарушены границы модулей:\n" + violations.joinToString("\n") { "  - $it" } +
                    "\n\nЕсли граница мешает — меняется правило в build.gradle.kts вместе с ADR, " +
                    "а не обходится в модуле."
            )
        }
    }
}

// --- Нативный код: сборка и проверка на хосте ------------------------------
//
// Кадровый буфер графики (native/canvas) — это C, работающий в двух процессах
// сразу. Ошибки такого кода не воспроизводятся по требованию, поэтому он
// собирается на хосте под санитайзерами и гоняется в CI, а не только едет в
// APK через NDK.
//
// ASan и TSan в одной сборке несовместимы — отсюда две задачи, а не одна.

val canvasDir = layout.projectDirectory.dir("native/canvas")

fun registerCanvasTest(name: String, sanitizer: String, description: String) =
    tasks.register<Exec>(name) {
        group = "verification"
        this.description = description

        val buildDir = layout.buildDirectory.dir("native-canvas/$name")
        inputs.dir(canvasDir)
        outputs.dir(buildDir)

        // Одной командой, чтобы не плодить задачи под configure и build:
        // проект крошечный, и полная пересборка занимает секунды.
        commandLine(
            "sh", "-c",
            "cmake -S ${canvasDir.asFile} -B ${buildDir.get().asFile} " +
                "-DEIDE_CANVAS_TESTS=ON -DEIDE_SANITIZER=$sanitizer -DCMAKE_BUILD_TYPE=Debug && " +
                "cmake --build ${buildDir.get().asFile} && " +
                "ctest --test-dir ${buildDir.get().asFile} --output-on-failure"
        )
    }

val canvasAsan = registerCanvasTest(
    "canvasTestAsan", "address,undefined",
    "Кадровый буфер под AddressSanitizer и UndefinedBehaviorSanitizer.",
)

val canvasTsan = registerCanvasTest(
    "canvasTestTsan", "thread",
    "Кадровый буфер под ThreadSanitizer: писатель и читатель в двух потоках.",
)

// Питоновский шим графики уезжает в APK, но ctypes не знает, что он на
// Android: та же библиотека собирается на хосте, и весь шим проверяется
// обычным запуском. Иначе опечатка в порядке аргументов ловится только на
// телефоне и выглядит как «просто не рисует».
val canvasShim = tasks.register<Exec>("canvasShimTest") {
    group = "verification"
    description = "Шим eide.py против настоящей libeide_canvas.so."

    val buildDir = layout.buildDirectory.dir("native-canvas/shim")
    inputs.dir(canvasDir)
    inputs.file(canvasDir.file("python/eide.py"))
    outputs.dir(buildDir)

    // PYTHONDONTWRITEBYTECODE не для чистоты: инвалидация .pyc идёт по времени
    // и размеру файла, и правка того же размера в ту же секунду остаётся
    // невидимой. Один раз это уже стоило получаса разбирательств.
    environment("PYTHONDONTWRITEBYTECODE", "1")
    environment("LD_LIBRARY_PATH", buildDir.get().asFile.absolutePath)

    commandLine(
        "sh", "-c",
        "cmake -S ${canvasDir.asFile} -B ${buildDir.get().asFile} -DCMAKE_BUILD_TYPE=Release && " +
            "cmake --build ${buildDir.get().asFile} && " +
            "python3 ${canvasDir.file("test/test_shim.py").asFile}"
    )
}

// Библиотека для десктопных проверок: её грузит и Python через ctypes, и
// сквозной тест графики. Отдельной задачей, чтобы путь был предсказуем.
val canvasLibrary = tasks.register<Exec>("canvasLibrary") {
    group = "build"
    description = "Собирает libeide_canvas.so для десктопа."

    val buildDir = layout.buildDirectory.dir("native-canvas/lib")
    inputs.dir(canvasDir)
    outputs.dir(buildDir)

    commandLine(
        "sh", "-c",
        "cmake -S ${canvasDir.asFile} -B ${buildDir.get().asFile} -DCMAKE_BUILD_TYPE=Release && " +
            "cmake --build ${buildDir.get().asFile}"
    )
}

tasks.register("checkNative") {
    group = "verification"
    description = "Все проверки нативного кода."
    dependsOn(canvasAsan, canvasTsan, canvasShim)
}

tasks.register("check") {
    group = "verification"
    dependsOn("checkArchitecture")
    dependsOn("checkNative")
    // :platform и :app — контейнеры без своего build-файла, у них нет задачи check.
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
}
