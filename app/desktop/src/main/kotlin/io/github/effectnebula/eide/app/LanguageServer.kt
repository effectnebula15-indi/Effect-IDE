package io.github.effectnebula.eide.app

import io.github.effectnebula.eide.core.lang.Languages
import io.github.effectnebula.eide.core.lsp.CompletionItem
import io.github.effectnebula.eide.core.lsp.DiagnosticMarks
import io.github.effectnebula.eide.core.lsp.LspSession
import io.github.effectnebula.eide.core.lsp.PositionEncoding
import io.github.effectnebula.eide.core.project.OpenFile
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.editor.CompletionSource
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * pylsp на десктопе: процесс и сессия над ним.
 *
 * Всё про синхронизацию текста, ошибки и порядок сообщений — в [LspSession];
 * здесь только то, что специфично для десктопа: как найти и запустить сервер
 * и как повторять за вкладками.
 *
 * Файл открывается у сервера, когда его показывают ([open]), и закрывается,
 * когда закрыли вкладку ([forgetClosed]). Раньше закрытые файлы серверу не
 * закрывались, и это было дёшево, пока от сервера нужно было только
 * автодополнение. С ошибками так нельзя: сервер держит и проверяет каждый
 * открытый файл, а открытым в нём оставалось бы всё, что человек когда-либо
 * открывал.
 */
class LanguageServer private constructor(
    private val process: Process,
    private val session: LspSession,
    val description: String,
) : AutoCloseable {

    /** Что открыто у сервера: файл и редактор, с которым он открыт. Поток интерфейса. */
    private val opened = HashMap<File, Opened>()

    private class Opened(val file: OpenFile, val marks: DiagnosticMarks)

    /**
     * Показывает файл серверу, если ещё не показан; возвращает его ошибки или
     * null, если язык файла серверу чужой. Поток интерфейса.
     *
     * Открывается по месту, при показе, а не отдельным шагом после: шаг после
     * пересборки опоздал бы, и редактор, собранный без ошибок, так и остался
     * бы без них — набор текста следующей пересборки экрана не вызывает.
     *
     * Сравнение по редактору, а не по имени: перечитанный с диска файл — это
     * новый `EditorState`, и сервер должен получить его текст заново.
     */
    fun open(file: OpenFile): DiagnosticMarks? {
        if (!isPython(file.file)) return null
        opened[file.file]?.let { known ->
            if (known.file.state === file.state) return known.marks
            session.close(file.file)
        }
        val marks = session.open(file.file, PYTHON, file.state)
        opened[file.file] = Opened(file, marks)
        return marks
    }

    /** Закрывает у сервера всё, чего нет среди [files]. Поток интерфейса. */
    fun forgetClosed(files: List<OpenFile>) {
        val states = files.map { it.state }
        for ((file, known) in opened.entries.toList()) {
            if (states.none { it === known.file.state }) {
                opened.remove(file)
                session.close(file)
            }
        }
    }

    /** Источник вариантов для файла, или null, если язык файла серверу чужой. */
    fun sourceFor(file: File): CompletionSource? {
        if (!isPython(file)) return null

        return object : CompletionSource {
            override val encoding: PositionEncoding get() = session.encoding

            // Текст сессия берёт из редактора сама: там же она решает, надо ли
            // сначала отправить его серверу. Снимок в аргументе — тот же объект.
            override fun complete(text: Rope, caret: Int): CompletableFuture<List<CompletionItem>> =
                session.completion(file, caret)
        }
    }

    override fun close() {
        runCatching { session.shutdown().get(SHUTDOWN_SECONDS, TimeUnit.SECONDS) }
        session.close()
        if (!process.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun isPython(file: File) = Languages.forFile(file.name).id == PYTHON

    companion object {
        private const val PYTHON = "python"
        private const val SHUTDOWN_SECONDS = 5L

        /**
         * Запускает pylsp, если он есть; иначе null и причина в [missingReason].
         *
         * Где искать: `EIDE_PYLSP`, затем `pylsp` в PATH, затем `python3 -m pylsp`.
         * Та же последовательность, что в тестах ядра, — чтобы «у меня в тестах
         * работает» и «в приложении работает» значило одно и то же.
         *
         * [ui] — поток интерфейса: через него ошибки доходят до редактора.
         */
        fun start(root: File, ui: Executor): LanguageServer? {
            val command = locate() ?: return null.also {
                missingReason = "pylsp не найден: поставьте python-lsp-server[pyflakes] или задайте EIDE_PYLSP"
            }
            val log = File.createTempFile("eide-pylsp-", ".log").apply { deleteOnExit() }

            return runCatching {
                // Поток ошибок — в файл, а не в трубу: непрочитанная труба
                // заполняется, и сервер замирает на записи в неё.
                val process = ProcessBuilder(command).directory(root).redirectError(log).start()
                val session = LspSession(process.inputStream, process.outputStream, ui)
                // Номер процесса на десктопе берётся из ProcessHandle: этот модуль
                // на телефон не едет. В ядре так нельзя — см. LspClient.initialize.
                session.initialize(root, ProcessHandle.current().pid())
                // В строке состояния — имя, а не полный путь: путь к виртуальному
                // окружению занимает полстроки и ничего не сообщает.
                val shown = listOf(File(command.first()).name) + command.drop(1)
                LanguageServer(process, session, shown.joinToString(" "))
            }.onFailure {
                missingReason = "pylsp не запустился: ${it.message}"
            }.getOrNull()
        }

        @Volatile
        var missingReason: String? = null
            private set

        private fun locate(): List<String>? {
            System.getenv("EIDE_PYLSP")?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
            for (candidate in listOf(listOf("pylsp"), listOf("python3", "-m", "pylsp"))) {
                val works = runCatching {
                    val probe = ProcessBuilder(candidate + "--version").redirectErrorStream(true).start()
                    probe.waitFor(PROBE_SECONDS, TimeUnit.SECONDS) && probe.exitValue() == 0
                }.getOrDefault(false)
                if (works) return candidate
            }
            return null
        }

        private const val PROBE_SECONDS = 20L
    }
}
