package io.github.effectnebula.eide.core.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Ошибка, которую вернул сервер в ответ на запрос. */
class ResponseError(val code: Int, message: String, val data: JsonElement? = null) :
    RuntimeException("[$code] $message")

/** Соединение закрылось, пока запрос ждал ответа. */
class ConnectionClosed(reason: String) : RuntimeException(reason)

/** Запрос в полёте: его номер и будущий ответ. Номер нужен, чтобы его отменить. */
class PendingCall(val id: Int, val result: CompletableFuture<JsonElement?>)

/**
 * JSON-RPC 2.0 поверх обрамления LSP.
 *
 * **Главное обещание: ни один запрос не повиснет навсегда.** Самодельные клиенты
 * чаще всего ломаются не на разборе, а на смерти сервера: процесс упал, поток
 * закрылся, а тот, кто ждёт ответа на автодополнение, ждёт вечно. Поэтому при
 * обрыве все незавершённые запросы завершаются ошибкой [ConnectionClosed].
 *
 * **Второе: сервер тоже задаёт вопросы, и на них обязательно отвечать.**
 * `window/workDoneProgress/create`, `client/registerCapability`,
 * `workspace/configuration` — серверы шлют их клиенту и ждут ответа, и иные
 * при этом замирают целиком. Поэтому на запрос, для которого у нас нет
 * обработчика, уходит ошибка «метод не найден», а не молчание; упавший
 * обработчик даёт «внутреннюю ошибку», а не смерть читающего потока.
 *
 * Потоки: один читающий поток на соединение, запись под замком. Ответы
 * приходят в [CompletableFuture], а не в корутины: `:core` не тянет корутины
 * ради одного места, а `await()` из них есть у того, кому они нужны.
 */
class JsonRpcConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val onNotification: (method: String, params: JsonElement?) -> Unit = { _, _ -> },
    private val onRequest: (method: String, params: JsonElement?) -> JsonElement? = { method, _ ->
        throw ResponseError(METHOD_NOT_FOUND, "метод $method не поддерживается")
    },
    /** Куда сообщить о смерти соединения — один раз, с причиной. */
    private val onClosed: (reason: String) -> Unit = {},
) : AutoCloseable {

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonElement?>>()
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    private val reader = Thread(::readLoop, "lsp-reader").apply { isDaemon = true }

    fun start() {
        reader.start()
    }

    fun request(method: String, params: JsonElement? = null): PendingCall {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonElement?>()

        // Сперва в таблицу, потом в поток: иначе быстрый сервер успеет ответить
        // раньше, чем мы запомним, кому этот ответ.
        pending[id] = future
        if (closed.get()) {
            pending.remove(id)
            future.completeExceptionally(ConnectionClosed("соединение закрыто"))
            return PendingCall(id, future)
        }

        runCatching {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                params?.let { put("params", it) }
            })
        }.onFailure { error ->
            pending.remove(id)
            future.completeExceptionally(error)
        }
        return PendingCall(id, future)
    }

    fun notify(method: String, params: JsonElement? = null) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            params?.let { put("params", it) }
        })
    }

    /**
     * Просит сервер бросить запрос.
     *
     * Ответ всё равно придёт — с результатом или с ошибкой «отменено», — и
     * будущее завершится им. Отмена здесь просьба, а не гарантия: сервер вправе
     * успеть досчитать. Нужна автодополнению: запрос на каждую букву, и ответы
     * на прошлые буквы никому не интересны.
     */
    fun cancel(id: Int) {
        if (pending.containsKey(id)) notify("\$/cancelRequest", buildJsonObject { put("id", id) })
    }

    override fun close() {
        shutDown("соединение закрыто клиентом")
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun send(message: JsonObject) {
        val bytes = Json.encodeToString(JsonObject.serializer(), message).toByteArray(Charsets.UTF_8)
        synchronized(writeLock) { LspWire.write(output, bytes) }
    }

    private fun readLoop() {
        val reason = try {
            while (!closed.get()) {
                val body = LspWire.read(input) ?: break
                dispatch(body)
            }
            "сервер закрыл поток"
        } catch (error: Exception) {
            if (closed.get()) "соединение закрыто клиентом" else "поток оборвался: ${error.message}"
        }
        shutDown(reason)
    }

    private fun dispatch(body: ByteArray) {
        val message = runCatching {
            Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        }.getOrElse {
            // Один испорченный ответ не повод рвать соединение: обрамление цело,
            // следующее сообщение прочитается. Но и молча проглотить его нельзя —
            // кто-то этого ответа ждёт, и дождётся он только закрытия.
            System.err.println("LSP: неразборчивое сообщение: ${it.message}")
            return
        }

        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val id = message["id"]
        val params = message["params"]

        when {
            method != null && id != null && id !is JsonNull -> answer(id, method, params)
            method != null -> runCatching { onNotification(method, params) }
                .onFailure { System.err.println("LSP: обработчик $method упал: $it") }
            else -> complete(message)
        }
    }

    /** Отвечает на запрос сервера — всегда, чем бы ни кончился обработчик. */
    private fun answer(id: JsonElement, method: String, params: JsonElement?) {
        val response = try {
            val result = onRequest(method, params)
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result ?: JsonNull)
            }
        } catch (error: ResponseError) {
            errorResponse(id, error.code, error.message ?: "")
        } catch (error: Exception) {
            errorResponse(id, INTERNAL_ERROR, "обработчик упал: ${error.message}")
        }
        runCatching { send(response) }
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun complete(message: JsonObject) {
        // Наши номера — целые. Ответ с чужим номером (строкой, дробным) не наш,
        // и ждать его некому.
        val id = (message["id"] as? JsonPrimitive)?.intOrNull ?: return
        val future = pending.remove(id) ?: return

        val error = message["error"] as? JsonObject
        if (error != null) {
            val code = error["code"]?.jsonPrimitive?.intOrNull ?: INTERNAL_ERROR
            val text = error["message"]?.jsonPrimitive?.contentOrNull ?: "без описания"
            future.completeExceptionally(ResponseError(code, text, error["data"]))
        } else {
            future.complete(message["result"]?.takeUnless { it is JsonNull })
        }
    }

    private fun shutDown(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        // Всё, что ждёт ответа, узнаёт о смерти соединения сейчас, а не никогда.
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(ConnectionClosed(reason)) }
        runCatching { onClosed(reason) }
    }

    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603

        /** Код, которым LSP отвечает на отменённый запрос. */
        const val REQUEST_CANCELLED = -32800
    }
}
