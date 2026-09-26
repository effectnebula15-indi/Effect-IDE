package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.text.Rope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture

/**
 * Позиция в терминах LSP: строка и столбец, оба с нуля.
 *
 * В чём считается столбец, решают переговоры — см. [PositionEncoding].
 */
data class LspPosition(val line: Int, val character: Int)

/**
 * В чём сервер считает столбцы.
 *
 * По спецификации — UTF-16 code units, если сервер не сказал иного. На деле
 * бывает иначе, и молча: см. [LspClient.positionEncoding].
 */
enum class PositionEncoding(val wire: String) {
    Utf16("utf-16"),

    /** Столбец в code points: один эмодзи — один знак, а не два. */
    Utf32("utf-32"),
}

data class LspRange(val start: LspPosition, val end: LspPosition)

/** Вариант автодополнения — только то, что умеем показать и вставить. */
data class CompletionItem(
    val label: String,
    /** Что вставить. Сервер может хотеть вставить не то, что показывает. */
    val insertText: String,
    val detail: String?,
    /** Участок, который вставка заменяет, если сервер его назвал. */
    val replaces: LspRange?,
    /** По чему сортировать. Порядок в массиве ответа спецификация не обещает. */
    val sortText: String? = null,
    /** По чему отбирать дописанным. Если нет — по подписи. */
    val filterText: String? = null,
)

data class Diagnostic(val range: LspRange, val severity: Severity, val message: String, val source: String?)

enum class Severity { Error, Warning, Information, Hint }

/**
 * Позиция LSP по офсету в тексте.
 *
 * Для UTF-16 перевод прямой: наш rope хранит текст в тех же code units
 * (ADR-005). Для UTF-32 считаются code points от начала строки — суррогатная
 * пара даёт один знак.
 */
fun Rope.toLspPosition(offset: Int, encoding: PositionEncoding = PositionEncoding.Utf16): LspPosition {
    val safe = offset.coerceIn(0, length)
    val line = lineOf(safe)
    val start = lineStart(line)
    val character = when (encoding) {
        PositionEncoding.Utf16 -> safe - start
        PositionEncoding.Utf32 -> substring(start, safe).let { it.codePointCount(0, it.length) }
    }
    return LspPosition(line, character)
}

/**
 * Офсет по позиции LSP, с подрезанием.
 *
 * Подрезаем, а не бросаем: позиция приходит от сервера, который видел текст
 * чуть раньше, чем мы, — человек успел напечатать ещё букву. Столбец режется по
 * концу своей строки, а не файла: иначе вставка уехала бы на следующую строку.
 */
fun Rope.offsetOf(position: LspPosition, encoding: PositionEncoding = PositionEncoding.Utf16): Int {
    val line = position.line.coerceIn(0, lineCount - 1)
    val start = lineStart(line)
    val end = lineEnd(line)
    val wanted = position.character.coerceAtLeast(0)

    return when (encoding) {
        PositionEncoding.Utf16 -> (start + wanted).coerceAtMost(end)
        PositionEncoding.Utf32 -> {
            val text = substring(start, end)
            // offsetByCodePoints бросает за концом строки — поэтому сперва подрезка.
            val points = minOf(wanted, text.codePointCount(0, text.length))
            start + text.offsetByCodePoints(0, points)
        }
    }
}

/**
 * URI файла в том виде, какой ждут серверы: `file:///путь`, не `file:/путь`.
 *
 * **Строится руками, а не через `toPath().toUri()`.** Путь через `java.nio.file`
 * зависит от `sun.jnu.encoding`: под `LANG=POSIX` кириллическое имя не портится,
 * как в `File`, а бросает `InvalidPathException` — и клиент LSP падал бы целиком
 * у всякого, у кого кириллица в пути к проекту. Нашлось тестом, а не у человека.
 * `URI` считает проценты сам, в UTF-8, и от локали не зависит.
 *
 * Путь Windows (`C:\проект`) превращается в `/C:/проект` — так его пишет
 * спецификация `file`-адресов.
 */
fun File.toLspUri(): String {
    val path = absolutePath.replace(File.separatorChar, '/').let { if (it.startsWith("/")) it else "/$it" }
    return java.net.URI("file", "", path, null, null).toASCIIString()
}

/**
 * Клиент LSP: ровно то, что нужно редактору, и ничего сверх.
 *
 * **Синхронизация документа — полным текстом.** Каждое изменение уходит текстом
 * файла целиком, а не диффом. Это всегда корректно (событие без `range` означает
 * «заменить всё» при любом режиме сервера) и не даёт разъехаться нашему тексту и
 * тексту сервера. Цена — объём: на каждое изменение уходит весь файл. Поэтому
 * изменения шлёт не каждая буква, а пауза в наборе, и поэтому потолок файла в
 * десять мегабайт (ADR-005) здесь тоже работает. Инкрементальная синхронизация —
 * следующий шаг, когда замер покажет, что полный текст дорог.
 *
 * **Сниппетов не просим.** Шаблон вида `range(${1:stop})` нам нечем развернуть,
 * а вставленный как есть он хуже, чем простое имя.
 *
 * Работает поверх любой пары потоков: процесс на десктопе, процесс-раннер на
 * телефоне, сокет к ПК — всё равно.
 */
class LspClient(
    input: InputStream,
    output: OutputStream,
    /**
     * Ошибки файла. [version] — версия документа, для которой они посчитаны,
     * если сервер её назвал; без неё — к какому тексту они относятся, неизвестно.
     * Вызывается из читающего потока.
     */
    private val onDiagnostics: (uri: String, version: Long?, diagnostics: List<Diagnostic>) -> Unit =
        { _, _, _ -> },
    onClosed: (reason: String) -> Unit = {},
) : AutoCloseable {

    /**
     * В чём этот сервер считает столбцы. Известно после [initialize].
     *
     * Сначала — что сервер сам сказал в ответе (`capabilities.positionEncoding`,
     * LSP 3.17). Если промолчал — по спецификации это UTF-16, **но** есть серверы,
     * которые молчат и считают иначе. Таких держим в [QUIRKS] по имени, явно,
     * а не угадываем: угаданная кодировка ломается тихо, неверным
     * автодополнением на строках с эмодзи.
     */
    @Volatile
    var positionEncoding: PositionEncoding = PositionEncoding.Utf16
        private set

    private val rpc = JsonRpcConnection(
        input = input,
        output = output,
        onNotification = ::onNotification,
        onRequest = ::onServerRequest,
        onClosed = onClosed,
    )

    /**
     * Инициализация: `initialize`, потом уведомление `initialized`. Без неё сервер молчит.
     *
     * [processId] — номер нашего процесса: по нему сервер сам выходит, если IDE
     * умерла, не попрощавшись. Передаётся снаружи, а не берётся из
     * `ProcessHandle`: тот появился в Java 9 и на Android отсутствует вовсе.
     * Сборка бы это пропустила — `:core` не Android-модуль, и lint его исходники
     * не смотрит, — а упало бы на устройстве у пользователя.
     */
    fun initialize(root: File?, processId: Long? = null): CompletableFuture<Unit> {
        rpc.start()
        val params = buildJsonObject {
            put("processId", processId)
            if (root != null) {
                put("rootUri", root.toLspUri())
                putJsonArray("workspaceFolders") {
                    add(buildJsonObject {
                        put("uri", root.toLspUri())
                        put("name", root.name)
                    })
                }
            } else {
                put("rootUri", JsonNull)
            }
            putJsonObject("capabilities") {
                putJsonObject("general") {
                    // Умеем обе; порядок — предпочтение. UTF-16 первой: это
                    // наше внутреннее представление, перевод в неё бесплатный.
                    putJsonArray("positionEncodings") {
                        add(JsonPrimitive(PositionEncoding.Utf16.wire))
                        add(JsonPrimitive(PositionEncoding.Utf32.wire))
                    }
                }
                putJsonObject("textDocument") {
                    putJsonObject("synchronization") { put("didSave", false) }
                    putJsonObject("completion") {
                        putJsonObject("completionItem") { put("snippetSupport", false) }
                    }
                    putJsonObject("publishDiagnostics") {
                        put("relatedInformation", false)
                        // Без версии ошибки нельзя привязать к тексту: пока сервер
                        // думал, человек мог допечатать, и позиции уже чужие.
                        put("versionSupport", true)
                    }
                }
            }
        }
        return rpc.request("initialize", params).result.thenApply { result ->
            positionEncoding = negotiatedEncoding(result as? JsonObject)
            rpc.notify("initialized", JsonObject(emptyMap()))
        }
    }

    /**
     * Версии документа задаёт вызывающий, а не клиент: по версии из ответа
     * (`publishDiagnostics`) надо понять, к какому тексту относятся ошибки, а
     * это знает только тот, кто держит документ. Версии обязаны расти — по ним
     * сервер отбрасывает запоздавшие изменения; `Document.version` растёт сам.
     */
    fun didOpen(uri: String, languageId: String, text: String, version: Long) {
        rpc.notify("textDocument/didOpen", buildJsonObject {
            putJsonObject("textDocument") {
                put("uri", uri)
                put("languageId", languageId)
                put("version", version)
                put("text", text)
            }
        })
    }

    fun didChange(uri: String, text: String, version: Long) {
        rpc.notify("textDocument/didChange", buildJsonObject {
            putJsonObject("textDocument") {
                put("uri", uri)
                put("version", version)
            }
            putJsonArray("contentChanges") {
                add(buildJsonObject { put("text", text) })
            }
        })
    }

    fun didClose(uri: String) {
        rpc.notify("textDocument/didClose", buildJsonObject {
            putJsonObject("textDocument") { put("uri", uri) }
        })
    }

    /**
     * Автодополнение в позиции.
     *
     * Возвращает номер запроса вместе с будущим ответом: автодополнение
     * спрашивается на каждую букву, и прошлый запрос надо уметь отменить
     * ([cancel]), а не дожидаться ответа, который уже никому не нужен.
     */
    fun completion(uri: String, position: LspPosition): Pair<Int, CompletableFuture<List<CompletionItem>>> {
        val call = rpc.request("textDocument/completion", buildJsonObject {
            putJsonObject("textDocument") { put("uri", uri) }
            putJsonObject("position") {
                put("line", position.line)
                put("character", position.character)
            }
        })
        return call.id to call.result.thenApply(::parseCompletion)
    }

    fun cancel(id: Int) = rpc.cancel(id)

    /**
     * Вежливое завершение: `shutdown`, потом `exit`.
     *
     * Сервер, которому не сказали `exit`, может остаться жить после нас; сервер,
     * которому сказали `exit` без `shutdown`, по спецификации выходит с ошибкой.
     */
    fun shutdown(): CompletableFuture<Unit> =
        rpc.request("shutdown").result.handle { _, _ ->
            runCatching { rpc.notify("exit") }
            Unit
        }

    override fun close() = rpc.close()

    private fun negotiatedEncoding(result: JsonObject?): PositionEncoding {
        val capabilities = result?.get("capabilities") as? JsonObject
        val announced = (capabilities?.get("positionEncoding") as? JsonPrimitive)?.contentOrNull
        if (announced != null) {
            return PositionEncoding.entries.firstOrNull { it.wire == announced } ?: PositionEncoding.Utf16
        }
        val name = ((result?.get("serverInfo") as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
        return QUIRKS[name] ?: PositionEncoding.Utf16
    }

    private fun onNotification(method: String, params: JsonElement?) {
        if (method != "textDocument/publishDiagnostics") return
        val body = params as? JsonObject ?: return
        val uri = body["uri"]?.jsonPrimitive?.contentOrNull ?: return
        val list = (body["diagnostics"] as? JsonArray).orEmpty().mapNotNull { parseDiagnostic(it) }
        onDiagnostics(uri, (body["version"] as? JsonPrimitive)?.longOrNull, list)
    }

    /**
     * Вопросы сервера к нам.
     *
     * Отвечаем на всё, что серверы задают на практике и без чего могут замереть.
     * Остальное уходит в [JsonRpcConnection], который ответит «метод не найден» —
     * молчать нельзя ни в каком случае.
     */
    private fun onServerRequest(method: String, params: JsonElement?): JsonElement? = when (method) {
        "window/workDoneProgress/create",
        "client/registerCapability",
        "client/unregisterCapability",
        "window/showMessageRequest" -> JsonNull

        // Настроек у нас нет — отвечаем «ничего» на каждый спрошенный пункт.
        // Длина ответа обязана совпасть с длиной вопроса, иначе сервер
        // сопоставит ответы не с теми пунктами.
        "workspace/configuration" -> {
            val items = (params as? JsonObject)?.get("items") as? JsonArray
            buildJsonArray { repeat(items?.size ?: 0) { add(JsonNull) } }
        }

        else -> throw ResponseError(JsonRpcConnection.METHOD_NOT_FOUND, "метод $method не поддерживается")
    }

    private fun parseCompletion(result: JsonElement?): List<CompletionItem> {
        // Ответ бывает трёх видов: null, массив вариантов или CompletionList.
        val items = when (result) {
            null, JsonNull -> return emptyList()
            is JsonArray -> result
            is JsonObject -> result["items"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val label = item["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val edit = item["textEdit"] as? JsonObject

            CompletionItem(
                label = label,
                insertText = edit?.get("newText")?.jsonPrimitive?.contentOrNull
                    ?: item["insertText"]?.jsonPrimitive?.contentOrNull
                    ?: label,
                detail = item["detail"]?.jsonPrimitive?.contentOrNull,
                replaces = (edit?.get("range") ?: edit?.get("replace"))?.let(::parseRange),
                sortText = item["sortText"]?.jsonPrimitive?.contentOrNull,
                filterText = item["filterText"]?.jsonPrimitive?.contentOrNull,
            )
        }.sortedWith(COMPLETION_ORDER)
    }

    private fun parseDiagnostic(element: JsonElement): Diagnostic? {
        val body = element as? JsonObject ?: return null
        val range = body["range"]?.let(::parseRange) ?: return null
        val message = body["message"]?.jsonPrimitive?.contentOrNull ?: return null

        // Без указания серьёзности спецификация велит клиенту решать самому.
        // Ошибкой не считаем: красное подчёркивание на подсказке пугает зря.
        val severity = when (body["severity"]?.jsonPrimitive?.intOrNull) {
            1 -> Severity.Error
            2 -> Severity.Warning
            4 -> Severity.Hint
            else -> Severity.Information
        }
        return Diagnostic(range, severity, message, body["source"]?.jsonPrimitive?.contentOrNull)
    }

    private fun parseRange(element: JsonElement): LspRange? {
        val body = element as? JsonObject ?: return null
        val start = body["start"]?.let(::parsePosition) ?: return null
        val end = body["end"]?.let(::parsePosition) ?: return null
        return LspRange(start, end)
    }

    companion object {
        /**
         * Серверы, которые молчат о кодировке и считают не в UTF-16.
         *
         * **pylsp** (проверено на 1.15.0): кодировку в ответе не объявляет, а
         * столбец берёт прямо индексом строки Python — то есть в code points.
         * Найдено замером, а не из документации: с позицией в UTF-16 после двух
         * эмодзи сервер видел курсор на два знака правее, префикс пропадал, и
         * вместо одного варианта приходило сто шестьдесят. В исходнике —
         * `Document.offset_at_position` и `word_at_position`.
         *
         * Запись снимается, когда сервер начнёт объявлять кодировку сам: тогда
         * сработает первая ветка [negotiatedEncoding], и таблица станет не нужна.
         */
        val QUIRKS: Map<String, PositionEncoding> = mapOf("pylsp" to PositionEncoding.Utf32)
    }

    private fun parsePosition(element: JsonElement): LspPosition? {
        val body = element as? JsonObject ?: return null
        val line = body["line"]?.jsonPrimitive?.intOrNull ?: return null
        val character = body["character"]?.jsonPrimitive?.intOrNull ?: return null
        return LspPosition(line, character)
    }
}
