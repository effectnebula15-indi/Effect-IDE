package io.github.effectnebula.eide.app

import io.github.effectnebula.eide.core.lang.Languages
import io.github.effectnebula.eide.core.lsp.CompletionItem
import io.github.effectnebula.eide.core.lsp.LspClient
import io.github.effectnebula.eide.core.lsp.PositionEncoding
import io.github.effectnebula.eide.core.lsp.toLspPosition
import io.github.effectnebula.eide.core.lsp.toLspUri
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.editor.CompletionSource
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * pylsp на десктопе: процесс, клиент и синхронизация открытых файлов.
 *
 * **Текст уходит серверу прямо перед запросом и только если изменился.** Пока
 * автодополнение — единственный потребитель, это даёт полное согласие текста
 * без таймеров и без лишних сообщений: сервер узнаёт о правке ровно тогда, когда
 * его о ней спрашивают. Изменился ли текст, видно по ссылке: rope неизменяемый,
 * и новая версия документа — это новый объект. Когда появится диагностика,
 * слать правки придётся самим, не дожидаясь вопроса, — тогда здесь заведётся
 * пауза в наборе.
 *
 * Закрытые файлы серверу не закрываются (`didClose` не шлётся): он держит их
 * текст до выхода. Цена — память сервера на десятке файлов; зато не нужно
 * следить за закрытием вкладок из этого класса.
 */
class LanguageServer private constructor(
    private val process: Process,
    private val client: LspClient,
    private val ready: CompletableFuture<Unit>,
    val description: String,
) : AutoCloseable {

    /** Что сервер уже знает о каждом файле: последний отправленный текст. */
    private val sent = ConcurrentHashMap<String, Rope>()

    /**
     * Источник вариантов для файла, или null, если язык файла серверу чужой.
     *
     * Текст берётся из аргумента, а не из редактора: вызов приходит в потоке
     * интерфейса со снимком, а отправка может случиться уже в потоке клиента,
     * когда сервер доинициализируется. Снимок неизменяем, читать его можно
     * откуда угодно; `EditorState` — нельзя.
     */
    fun sourceFor(file: File): CompletionSource? {
        val language = Languages.forFile(file.name)
        if (language.id != "python") return null
        val uri = file.toLspUri()

        return object : CompletionSource {
            override val encoding: PositionEncoding get() = client.positionEncoding

            override fun complete(text: Rope, caret: Int): CompletableFuture<List<CompletionItem>> =
                ready.thenCompose {
                    sync(uri, language.id, text)
                    client.completion(uri, text.toLspPosition(caret, client.positionEncoding)).second
                }
        }
    }

    private fun sync(uri: String, languageId: String, text: Rope) {
        val previous = sent.put(uri, text)
        when {
            previous == null -> client.didOpen(uri, languageId, text.substring(0, text.length))
            previous !== text -> client.didChange(uri, text.substring(0, text.length))
        }
    }

    override fun close() {
        runCatching { client.shutdown().get(SHUTDOWN_SECONDS, TimeUnit.SECONDS) }
        client.close()
        if (!process.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        private const val SHUTDOWN_SECONDS = 5L

        /**
         * Запускает pylsp, если он есть; иначе null и причина в [missingReason].
         *
         * Где искать: `EIDE_PYLSP`, затем `pylsp` в PATH, затем `python3 -m pylsp`.
         * Та же последовательность, что в тестах ядра, — чтобы «у меня в тестах
         * работает» и «в приложении работает» значило одно и то же.
         */
        fun start(root: File): LanguageServer? {
            val command = locate() ?: return null.also {
                missingReason = "pylsp не найден: поставьте python-lsp-server или задайте EIDE_PYLSP"
            }
            val log = File.createTempFile("eide-pylsp-", ".log").apply { deleteOnExit() }

            return runCatching {
                // Поток ошибок — в файл, а не в трубу: непрочитанная труба
                // заполняется, и сервер замирает на записи в неё.
                val process = ProcessBuilder(command).directory(root).redirectError(log).start()
                val client = LspClient(process.inputStream, process.outputStream)
                // Номер процесса на десктопе берётся из ProcessHandle: этот модуль
                // на телефон не едет. В ядре так нельзя — см. LspClient.initialize.
                val ready = client.initialize(root, ProcessHandle.current().pid())
                // В строке состояния — имя, а не полный путь: путь к виртуальному
                // окружению занимает полстроки и ничего не сообщает.
                val shown = listOf(File(command.first()).name) + command.drop(1)
                LanguageServer(process, client, ready, shown.joinToString(" "))
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
