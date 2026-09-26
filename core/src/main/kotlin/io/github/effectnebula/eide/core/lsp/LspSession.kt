package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.editor.EditorListener
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Rope
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Сервер языка вместе с открытыми у него файлами: текст, автодополнение, ошибки.
 *
 * Всё, что между «человек правит файл» и «сервер знает об этом», живёт здесь,
 * а не в точке сборки: десктопу и телефону нужно одно и то же, отличается
 * только то, откуда взялись потоки к серверу.
 *
 * **Потоки.** [open], [close], [completion] и правки редактора — из потока
 * интерфейса: `EditorState` иначе читать нельзя. Серверу пишет один отдельный
 * поток, по очереди. Отдельный — потому что запись в трубу блокируется, пока
 * сервер не прочтёт, а занятый pylsp не читает: запись полного текста большого
 * файла из потока интерфейса заморозила бы экран. По очереди — потому что
 * `didOpen` обязан прийти раньше `didChange`, а тот — раньше запроса,
 * посчитанного по новому тексту. Ошибки от сервера возвращаются через [ui].
 *
 * **Правки уходят после паузы** в [syncPauseMs], а не на каждую букву: каждое
 * изменение — это файл целиком (см. [LspClient]). Цена паузы — задержка:
 * ошибка появляется через паузу плюс раздумья сервера (у pylsp ещё полсекунды,
 * замерено). Автодополнение паузы не ждёт: перед запросом текст уходит сразу.
 */
class LspSession(
    input: InputStream,
    output: OutputStream,
    /** Поток интерфейса. Через него приходят ошибки к [DiagnosticMarks]. */
    private val ui: Executor,
    private val syncPauseMs: Long = SYNC_PAUSE_MS,
    onClosed: (reason: String) -> Unit = {},
) : AutoCloseable {

    private val client = LspClient(input, output, ::published, onClosed)

    // И очередь записи, и таймер паузы: один поток — один порядок.
    private val writer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "lsp-writer").apply { isDaemon = true }
    }.apply {
        // Отложенная отправка после закрытия не нужна никому. Уже поставленное
        // в очередь, наоборот, доработает: см. close.
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    // Один и тот же future с рождения, а не подменяемый в initialize: иначе
    // open, вызванный раньше initialize, прочёл бы старый и потерял didOpen.
    private val ready = CompletableFuture<Unit>()

    private val documents = ConcurrentHashMap<String, OpenDocument>()

    /** В чём сервер считает столбцы. Известно после [initialize]. */
    val encoding: PositionEncoding get() = client.positionEncoding

    /** Всё, что попросили раньше, уйдёт серверу после неё, в том же порядке. */
    fun initialize(root: File?, processId: Long? = null): CompletableFuture<Unit> {
        client.initialize(root, processId).whenComplete { _, error ->
            if (error == null) ready.complete(Unit) else ready.completeExceptionally(error)
        }
        return ready
    }

    /**
     * Показывает файл серверу. Возвращает его ошибки — они же едут за правками.
     *
     * Повторное открытие того же файла отдаёт уже открытое: второй `didOpen`
     * без `didClose` сервер вправе счесть ошибкой.
     */
    fun open(file: File, languageId: String, state: EditorState): DiagnosticMarks {
        val uri = file.toLspUri()
        documents[key(uri)]?.let { if (it.state === state) return it.marks else close(file) }

        val document = OpenDocument(uri, state, DiagnosticMarks(state), state.document.version)
        documents[key(uri)] = document
        val text = state.text
        val version = document.sentVersion
        send { client.didOpen(uri, languageId, text.whole(), version) }

        state.addListener(document.onEdit)
        return document.marks
    }

    fun close(file: File) {
        val document = documents.remove(key(file.toLspUri())) ?: return
        // Отложенную отправку не отменяем: сработав, она увидит, что документа
        // уже нет, и ничего не сделает.
        document.state.removeListener(document.onEdit)
        document.marks.close()
        send { client.didClose(document.uri) }
    }

    /**
     * Варианты в позиции [caret] открытого файла; пусто, если файл не открыт.
     *
     * Текст уходит серверу перед запросом, если изменился: спрашивать сервер
     * о тексте, которого он не видел, — получать варианты для чужого места.
     */
    fun completion(file: File, caret: Int): CompletableFuture<List<CompletionItem>> {
        val document = documents[key(file.toLspUri())]
            ?: return CompletableFuture.completedFuture(emptyList())
        sync(document)
        val text = document.state.text
        val call = CompletableFuture<List<CompletionItem>>()
        val queued = enqueue {
            runCatching {
                ready.join()
                client.completion(document.uri, text.toLspPosition(caret, client.positionEncoding)).second
            }.fold(
                onSuccess = { answer -> answer.whenComplete { items, error -> call.finish(items, error) } },
                onFailure = { call.completeExceptionally(it) },
            )
        }
        if (!queued) call.completeExceptionally(ConnectionClosed("сессия закрыта"))
        return call
    }

    /** Вежливо прощается с сервером; см. [LspClient.shutdown]. */
    fun shutdown(): CompletableFuture<Unit> {
        val done = CompletableFuture<Unit>()
        val queued = enqueue {
            runCatching { ready.join(); client.shutdown() }.fold(
                onSuccess = { it.whenComplete { _, _ -> done.complete(Unit) } },
                onFailure = { done.complete(Unit) },
            )
        }
        if (!queued) done.complete(Unit)
        return done
    }

    /**
     * Закрывает соединение. Всё, что ещё ждёт ответа, получает ошибку, а не вечность.
     *
     * `shutdown`, а не `shutdownNow`: второй выбрасывает из очереди задачи, не
     * успевшие начаться, и их future не завершаются никогда — автодополнение ждало
     * бы вечно. Так было в первой редакции; поймал тест. Оставшиеся задачи
     * дорабатывают быстро: инициализация провалена здесь же, и они падают на ней.
     */
    override fun close() {
        ready.completeExceptionally(ConnectionClosed("сессия закрыта"))
        client.close()
        writer.shutdown()
    }

    /** Отправляет текст, если сервер видел не последнюю версию. Поток интерфейса. */
    private fun sync(document: OpenDocument) {
        document.pendingSync?.cancel(false)
        document.pendingSync = null
        val version = document.state.document.version
        if (version == document.sentVersion) return
        document.sentVersion = version
        val text = document.state.text
        send { client.didChange(document.uri, text.whole(), version) }
    }

    /** Правка в редакторе: отложить отправку до паузы. Поток интерфейса. */
    private fun edited(document: OpenDocument) {
        if (document.state.document.version == document.sentVersion) return
        document.pendingSync?.cancel(false)
        document.pendingSync = try {
            writer.schedule(
                // Сама отправка решается в потоке интерфейса: только там можно
                // прочесть EditorState, и только там верен порядок версий.
                { ui.execute { if (documents[key(document.uri)] === document) sync(document) } },
                syncPauseMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            null // сессия закрыта — отправлять некуда
        }
    }

    /** Ответ сервера с ошибками. Читающий поток — отсюда только в [ui]. */
    private fun published(uri: String, version: Long?, diagnostics: List<Diagnostic>) {
        ui.execute {
            val document = documents[key(uri)] ?: return@execute
            // Без версии — считаем, что про последний отправленный текст. Если с
            // тех пор допечатали, DiagnosticMarks сам выбросит устаревшее.
            document.marks.publish(version ?: document.sentVersion, diagnostics, client.positionEncoding)
        }
    }

    private fun send(action: () -> Unit) {
        enqueue {
            // Сервер не поднялся или умер — писать некуда; о смерти уже сообщено.
            runCatching {
                ready.join()
                action()
            }
        }
    }

    /** В очередь записи; `false` — сессия закрыта. Бросать в поток интерфейса нельзя. */
    private fun enqueue(task: () -> Unit): Boolean = try {
        writer.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private inner class OpenDocument(
        val uri: String,
        val state: EditorState,
        val marks: DiagnosticMarks,
        /** Какую версию сервер уже видел. Только поток интерфейса. */
        var sentVersion: Long,
    ) {
        var pendingSync: ScheduledFuture<*>? = null
        val onEdit = EditorListener { edited(this) }
    }

    companion object {
        const val SYNC_PAUSE_MS = 300L

        /**
         * Ключ для сопоставления адресов: путь, а не строка адреса.
         *
         * Сервер вправе вернуть адрес в другой записи — `%D0` вместо `%d0`,
         * без лишних слешей, — а файл тот же. Строки бы разошлись, и ошибки
         * файла с кириллицей в имени не доходили бы никуда. Чего это не лечит:
         * регистр буквы диска на Windows (pylsp её понижает) — не проверено.
         */
        private fun key(uri: String): String = runCatching { URI(uri).path }.getOrNull() ?: uri
    }
}

private fun Rope.whole(): String = substring(0, length)

private fun <T> CompletableFuture<T>.finish(value: T?, error: Throwable?) {
    if (error != null) completeExceptionally(error) else complete(value)
}
