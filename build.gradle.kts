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
        ":ui" to setOf(":core", ":platform:api"),
        ":app:desktop" to setOf(":core", ":platform:api", ":platform:desktop", ":ui", ":runner"),
        ":app:android" to setOf(":core", ":platform:api", ":platform:android", ":ui", ":runner"),
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

tasks.register("check") {
    group = "verification"
    dependsOn("checkArchitecture")
    // :platform и :app — контейнеры без своего build-файла, у них нет задачи check.
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
}
