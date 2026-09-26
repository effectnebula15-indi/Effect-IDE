package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Rope
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Клиент против настоящего pylsp.
 *
 * Поддельный сервер проверяет то, что мы о протоколе думаем; этот тест — что
 * думаем правильно. Разбор, обрамление и вопросы сервера здесь сходятся с чужой
 * реализацией, у которой свои привычки.
 *
 * **Нет pylsp — тест пропущен, и это видно.** Пропуск помечается в отчёте, а не
 * проходит молча. В CI pylsp ставится явно и с прибитой версией, так что там
 * тест идёт всегда; пропуск возможен только на машине разработчика.
 *
 * Где искать сервер: `EIDE_PYLSP` (путь к исполняемому), иначе `pylsp` в PATH,
 * иначе `python3 -m pylsp`.
 */
class PylspTest {

    private var process: Process? = null
    private var client: LspClient? = null
    private var session: LspSession? = null
    private val stderr: File = File.createTempFile("pylsp-", ".log").apply { deleteOnExit() }

    /** «Поток интерфейса» для сессии: редактор и пометки трогаются только в нём. */
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "ui").apply { isDaemon = true } }

    private fun <T> onUi(block: () -> T): T = ui.submit(block).get(10, TimeUnit.SECONDS)

    @AfterTest
    fun cleanup() {
        runCatching { client?.shutdown()?.get(10, TimeUnit.SECONDS) }
        client?.close()
        runCatching { session?.shutdown()?.get(10, TimeUnit.SECONDS) }
        session?.close()
        ui.shutdownNow()
        process?.let {
            if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly()
        }
    }

    private fun command(): List<String>? {
        System.getenv("EIDE_PYLSP")?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        for (candidate in listOf(listOf("pylsp"), listOf("python3", "-m", "pylsp"))) {
            val works = runCatching {
                val probe = ProcessBuilder(candidate + "--version").redirectErrorStream(true).start()
                probe.waitFor(20, TimeUnit.SECONDS) && probe.exitValue() == 0
            }.getOrDefault(false)
            if (works) return candidate
        }
        return null
    }

    /** Запускает процесс сервера; нет сервера — тест пропущен. */
    private fun launch(): Process {
        val command = command()
        assumeTrue(command != null, "pylsp не найден: задайте EIDE_PYLSP или поставьте python-lsp-server")

        // Поток ошибок уходит в файл, а не в никуда и не в трубу: непрочитанная
        // труба заполняется, и сервер замирает на записи в неё. А файл остаётся,
        // чтобы было что прочесть, если тест упал.
        return ProcessBuilder(command!!).redirectError(stderr).start().also { process = it }
    }

    /** Запускает сервер и проводит инициализацию. */
    private fun start(root: File): LspClient {
        val started = launch()
        val lsp = LspClient(started.inputStream, started.outputStream)
        client = lsp

        lsp.initialize(root, processId = null).get(60, TimeUnit.SECONDS)
        return lsp
    }

    private fun project(): File =
        Files.createTempDirectory("eide-pylsp").toFile().apply { deleteOnExit() }

    @Test
    fun `pylsp completes a module attribute`() {
        val root = project()
        val lsp = start(root)
        val uri = File(root, "main.py").toLspUri()
        val text = Rope.of("import os\nos.pa")

        lsp.didOpen(uri, "python", text.substring(0, text.length), version = 1)
        val (_, answer) = lsp.completion(uri, text.toLspPosition(text.length, lsp.positionEncoding))
        val labels = answer.get(60, TimeUnit.SECONDS).map { it.label }

        assertTrue(labels.any { it.startsWith("path") }, "ожидали path среди $labels; stderr: ${stderr.readText()}")
    }

    @Test
    fun `positions after emoji agree with the server`() {
        // Эмодзи — два code unit'а UTF-16 и один знак. Если клиент и сервер
        // считают столбцы по-разному, сервер видит курсор не там, где он стоит.
        // Проверить это можно только против настоящего сервера: подделка считает
        // так же, как мы.
        //
        // **Этот тест дважды не проверял ничего.** Сначала эмодзи был один, и
        // сдвиг на единицу давал префикс «зна» вместо «знач» — то же имя. Потом
        // эмодзи стало два, но курсор стоял в конце строки, а позицию за концом
        // сервер подрезает — и ошибка кодировки снова проглатывалась. Теперь
        // справа от курсора текст, и сдвиг уводит его в `+ 1`.
        //
        // Именно этот тест и нашёл, что pylsp считает в code points, хотя
        // объявляет (молчанием) UTF-16: см. `LspClient.QUIRKS`.
        val root = project()
        val lsp = start(root)
        val uri = File(root, "main.py").toLspUri()
        val line = "s = \"\uD83D\uDE00\uD83D\uDE00\" + зн + 1"
        val text = Rope.of("значение_x = 1\n$line")
        val cursor = text.lineStart(1) + line.indexOf("зн") + 2

        lsp.didOpen(uri, "python", text.substring(0, text.length), version = 1)
        val (_, answer) = lsp.completion(uri, text.toLspPosition(cursor, lsp.positionEncoding))
        val labels = answer.get(60, TimeUnit.SECONDS).map { it.label }

        assertTrue("значение_x" in labels, "ожидали значение_x среди $labels; stderr: ${stderr.readText()}")
        assertTrue(
            labels.all { it.startsWith("зн") },
            "сервер понял курсор не там, где он стоит: в ответе чужие имена $labels",
        )
    }

    @Test
    fun `pylsp leaves after shutdown and exit`() {
        // Сервер, который пережил IDE, — это процесс, который висит в системе
        // до перезагрузки. На телефоне — ещё и батарея.
        val lsp = start(project())

        lsp.shutdown().get(30, TimeUnit.SECONDS)
        client = null

        assertTrue(process!!.waitFor(30, TimeUnit.SECONDS), "pylsp не вышел после exit; stderr: ${stderr.readText()}")
    }

    @Test
    fun `pylsp errors land on their words and leave when fixed`() {
        // Весь путь ошибки: правка → пауза → didChange → pyflakes → ответ с
        // версией → поток интерфейса → пометка на нужном слове. Имя файла
        // кириллицей: адрес уходит с процентами, и вернуться он должен к тому же
        // файлу, в какой записи его ни верни сервер.
        val started = launch()
        val lsp = LspSession(started.inputStream, started.outputStream, ui, syncPauseMs = 100)
        session = lsp
        val root = project()
        lsp.initialize(root).get(60, TimeUnit.SECONDS)

        val state = onUi { testEditor("import os\nx = неизвестное_имя\n") }
        val marks = onUi { lsp.open(File(root, "проверка.py"), "python", state) }

        val error = waitFor("ошибка про неизвестное имя") {
            onUi { marks.marks.firstOrNull { it.severity == Severity.Error } }
        }
        assertEquals("неизвестное_имя", onUi { state.text.substring(error.start, error.end) })

        val unused = onUi { marks.marks.single { it.severity == Severity.Warning } }
        assertEquals("import os", onUi { state.text.substring(unused.start, unused.end) })

        // Исправили. Пометка на заменённом слове пропадёт сама, без сервера, —
        // поэтому ждём другое: предупреждение про `import os`. Правка его не
        // задевает, и убрать его может только новый ответ pylsp, увидевшего
        // `os.sep`. Первая редакция теста ждала пропажи ошибки и проходила,
        // не дождавшись сервера вовсе.
        onUi { state.replaceAll(EditTransaction.replace(error.start, error.end, "os.sep")) }
        waitFor("сервер пересчитал ошибки после исправления") {
            onUi { marks.marks.isEmpty().takeIf { it } }
        }
    }

    /**
     * Ждёт, пока [probe] вернёт не null. Ошибки pylsp присылает через полсекунды
     * после правки, а первый раз — после разогрева, поэтому ожидание щедрое.
     */
    private fun <T : Any> waitFor(what: String, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        error(
            "не дождались: $what. Если ошибок нет вовсе — у pylsp нет pyflakes " +
                "(ставьте python-lsp-server[pyflakes]). stderr: ${stderr.readText()}",
        )
    }
}
