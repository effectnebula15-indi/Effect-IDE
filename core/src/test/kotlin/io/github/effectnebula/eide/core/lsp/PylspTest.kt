package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.text.Rope
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
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
    private val stderr: File = File.createTempFile("pylsp-", ".log").apply { deleteOnExit() }

    @AfterTest
    fun cleanup() {
        runCatching { client?.shutdown()?.get(10, TimeUnit.SECONDS) }
        client?.close()
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

    /** Запускает сервер и проводит инициализацию. */
    private fun start(root: File): LspClient {
        val command = command()
        assumeTrue(command != null, "pylsp не найден: задайте EIDE_PYLSP или поставьте python-lsp-server")

        // Поток ошибок уходит в файл, а не в никуда и не в трубу: непрочитанная
        // труба заполняется, и сервер замирает на записи в неё. А файл остаётся,
        // чтобы было что прочесть, если тест упал.
        val started = ProcessBuilder(command!!).redirectError(stderr).start()
        process = started
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

        lsp.didOpen(uri, "python", text.substring(0, text.length))
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

        lsp.didOpen(uri, "python", text.substring(0, text.length))
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
}
