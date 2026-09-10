import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    id("eide.android-library")
}

// --- Официальная сборка CPython под Android ---------------------------------
//
// Берём готовый пакет с python.org (ADR-003), а не собираем сами и не тащим
// Chaquopy: это официально поддерживаемая платформа CPython, пакет обновляется
// вместе с каждым релизом Python, и в нём уже лежит _ctypes — без него не
// заработает графический шим (ADR-004).
//
// В репозиторий пакет не кладём: 22 МБ бинарников в git — плохая идея.
// Качаем на сборке и сверяем sha256, чтобы подмена не проходила молча.

val pythonVersion = libs.versions.python.get()
val pyXY = pythonVersion.substringBeforeLast('.')
val pythonUrl =
    "https://www.python.org/ftp/python/$pythonVersion/python-$pythonVersion-aarch64-linux-android.tar.gz"

val pythonArchive = layout.buildDirectory.file("python/python-$pythonVersion-aarch64-linux-android.tar.gz")
val pythonPrefix = layout.buildDirectory.dir("python/prefix")

abstract class DownloadPython : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val archive: RegularFileProperty

    @TaskAction
    fun run() {
        val target = archive.get().asFile
        target.parentFile.mkdirs()
        logger.lifecycle("Качаю CPython: ${url.get()}")
        URI(url.get()).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            target.delete()
            throw GradleException(
                "sha256 скачанного CPython не совпал.\n  ожидали: ${sha256.get()}\n  получили: $actual"
            )
        }
    }
}

/** Копирует часть распакованного prefix в каталог, который AGP подхватит как источник. */
abstract class StagePython : DefaultTask() {
    @get:InputDirectory abstract val prefix: DirectoryProperty
    @get:Input abstract val spec: Property<String> // "jniLibs" или "assets"
    @get:Input abstract val pythonXY: Property<String>

    /** Наш собственный код на Python: шим графики и что появится дальше. */
    @get:InputDirectory @get:Optional abstract val shim: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun run() {
        val prefixDir = prefix.get().asFile
        when (spec.get()) {
            // Только эти библиотеки едут в jniLibs — так предписывает
            // документация CPython по встраиванию в приложение.
            "jniLibs" -> fs.sync {
                from("$prefixDir/lib") {
                    include("libpython*.*.so")
                    include("lib*_python.so")
                }
                into(outputDir.get().asFile.resolve("arm64-v8a"))
            }
            // Стандартная библиотека едет в assets и распаковывается при первом
            // запуске. Выкидываем то, чему на телефоне делать нечего: один только
            // test/ весит 37 МБ при бюджете APK в 60 МБ.
            "assets" -> {
                fs.sync {
                    from("$prefixDir/lib/python${pythonXY.get()}") {
                        exclude("test/**", "idlelib/**", "ensurepip/**", "tkinter/**")
                        exclude("pydoc_data/**", "turtledemo/**", "**/__pycache__/**")
                    }
                    into(outputDir.get().asFile.resolve("python/lib/python${pythonXY.get()}"))
                }
                // site-packages — место, куда официальная документация CPython
                // велит класть свой код. Копируем после sync: тот чистит каталог.
                if (shim.isPresent) {
                    fs.copy {
                        from(shim.get().asFile) { include("*.py") }
                        into(
                            outputDir.get().asFile
                                .resolve("python/lib/python${pythonXY.get()}/site-packages")
                        )
                    }
                }
            }
            else -> error("неизвестная часть: ${spec.get()}")
        }
    }
}

val downloadPython = tasks.register<DownloadPython>("downloadPython") {
    description = "Скачивает официальную сборку CPython под Android и сверяет sha256."
    url.set(pythonUrl)
    sha256.set(libs.versions.pythonAndroidSha256.get())
    archive.set(pythonArchive)
}

val unpackPython = tasks.register<Sync>("unpackPython") {
    description = "Распаковывает prefix из скачанного пакета CPython."
    // tarTree(resources.gzip(...)) теряет связь с задачей-производителем файла,
    // поэтому зависимость объявляем явно.
    dependsOn(downloadPython)
    from(tarTree(resources.gzip(downloadPython.flatMap { it.archive }))) {
        include("prefix/**")
        eachFile { path = path.removePrefix("prefix/") }
        includeEmptyDirs = false
    }
    into(pythonPrefix)
}

val stagePythonJniLibs = tasks.register<StagePython>("stagePythonJniLibs") {
    prefix.set(pythonPrefix)
    spec.set("jniLibs")
    pythonXY.set(pyXY)
    dependsOn(unpackPython)
}

val stagePythonAssets = tasks.register<StagePython>("stagePythonAssets") {
    prefix.set(pythonPrefix)
    spec.set("assets")
    pythonXY.set(pyXY)
    // Шим лежит рядом с библиотекой, которую оборачивает, а не в модуле
    // Android: сам он платформы не знает, и десктопная проверка берёт его
    // оттуда же.
    shim.set(rootProject.layout.projectDirectory.dir("native/canvas/python"))
    dependsOn(unpackPython)
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(stagePythonJniLibs, StagePython::outputDir)
    variant.sources.assets?.addGeneratedSourceDirectory(stagePythonAssets, StagePython::outputDir)
}

android {
    namespace = "io.github.effectnebula.eide.runner.android"

    defaultConfig {
        // Официальная сборка CPython у нас только под arm64 — собирать натив
        // под остальные ABI нечем и незачем.
        ndk.abiFilters.add("arm64-v8a")

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DPYTHON_PREFIX_DIR=${pythonPrefix.get().asFile.absolutePath}",
                    "-DPYTHON_VERSION=$pyXY",
                    "-DEIDE_CANVAS_DIR=${rootProject.layout.projectDirectory.dir("native/canvas").asFile}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// CMake линкуется с libpython — значит распаковка обязана произойти раньше.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(unpackPython) }

dependencies {
    implementation(project(":core"))
}
