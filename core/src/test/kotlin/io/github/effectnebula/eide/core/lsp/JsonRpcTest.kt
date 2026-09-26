package io.github.effectnebula.eide.core.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON-RPC поверх обрамления LSP.
 *
 * Сервер здесь поддельный — это сам тест: он читает то, что пишет клиент, и
 * отвечает руками. Так проверяется ровно то, что ломается у настоящих серверов:
 * ответы не по порядку, встречные запросы, смерть посреди работы.
 *
 * **Каждое ожидание ответа — с таймаутом.** Главное обещание соединения —
 * «ни один запрос не повиснет навсегда», и тест, проверяющий его через `get()`
 * без таймаута, при поломке не упадёт, а повиснет сам.
 */
class JsonRpcTest {

    private val server = FakeServer()

    private val notifications = CopyOnWriteArrayList<Pair<String, JsonElement?>>()
    private val closeReasons = CopyOnWriteArrayList<String>()

    private fun connect(
        onRequest: (String, JsonElement?) -> JsonElement? = { method, _ ->
            throw ResponseError(JsonRpcConnection.METHOD_NOT_FOUND, "нет $method")
        },
        onClosed: (String) -> Unit = { closeReasons += it },
    ) = JsonRpcConnection(
        input = server.clientInput,
        output = server.clientOutput,
        onNotification = { method, params -> notifications += method to params },
        onRequest = onRequest,
        onClosed = onClosed,
    ).also { it.start() }

    private fun received(): JsonObject = server.received()

    private fun reply(json: String) = server.reply(json)

    private fun <T> java.util.concurrent.Future<T>.await(): T = get(5, TimeUnit.SECONDS)

    @AfterTest
    fun cleanup() = server.close()

    @Test
    fun `answers find their own requests even out of order`() {
        val rpc = connect()
        val first = rpc.request("первый")
        val second = rpc.request("второй")
        val firstId = received()["id"]!!.jsonPrimitive.int
        val secondId = received()["id"]!!.jsonPrimitive.int

        // Сервер вправе отвечать в любом порядке: быстрый ответ раньше медленного.
        reply("""{"jsonrpc":"2.0","id":$secondId,"result":"для второго"}""")
        reply("""{"jsonrpc":"2.0","id":$firstId,"result":"для первого"}""")

        assertEquals(JsonPrimitive("для первого"), first.result.await())
        assertEquals(JsonPrimitive("для второго"), second.result.await())
    }

    @Test
    fun `an error response fails the call with its code`() {
        val rpc = connect()
        val call = rpc.request("сломанный")
        val id = received()["id"]!!.jsonPrimitive.int

        reply("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"плохие параметры"}}""")

        val error = assertFailsWith<ExecutionException> { call.result.await() }.cause
        assertIs<ResponseError>(error)
        assertEquals(-32602, error.code)
    }

    @Test
    fun `a null result is a result, not a failure`() {
        val rpc = connect()
        val call = rpc.request("пустой")
        val id = received()["id"]!!.jsonPrimitive.int

        reply("""{"jsonrpc":"2.0","id":$id,"result":null}""")

        assertNull(call.result.await())
    }

    @Test
    fun `a dead server fails every waiting call instead of leaving it hanging`() {
        // Главное обещание соединения. Ломается у самодельных клиентов чаще всего:
        // сервер упал, а автодополнение ждёт ответа вечно.
        val rpc = connect()
        val first = rpc.request("первый")
        val second = rpc.request("второй")
        received()
        received()

        server.die()

        assertIs<ConnectionClosed>(assertFailsWith<ExecutionException> { first.result.await() }.cause)
        assertIs<ConnectionClosed>(assertFailsWith<ExecutionException> { second.result.await() }.cause)
        rpc.close()
        assertEquals(1, closeReasons.size, "о смерти соединения сообщается один раз")
    }

    @Test
    fun `the owner hears about the death before any waiting call does`() {
        // Медленный onClosed делает гонку, которую CI поймала однажды, воспроизводимой
        // всегда: при обратном порядке ожидающий проснётся раньше, чем onClosed допишет.
        val rpc = connect(onClosed = { reason ->
            Thread.sleep(200)
            closeReasons += reason
        })
        val call = rpc.request("ждущий")
        received()

        server.die()

        assertIs<ConnectionClosed>(assertFailsWith<ExecutionException> { call.result.await() }.cause)
        assertEquals(1, closeReasons.size, "проснувшийся вызов должен уже видеть последствия onClosed")
        rpc.close()
    }

    @Test
    fun `a call after the connection died fails at once`() {
        val rpc = connect()
        server.die()
        rpc.close()

        val call = rpc.request("поздний")

        assertIs<ConnectionClosed>(assertFailsWith<ExecutionException> { call.result.await() }.cause)
    }

    @Test
    fun `a server request without a handler is still answered`() {
        // Серверы ждут ответа на свои вопросы, и иные при этом замирают целиком.
        // Молчание здесь хуже любой ошибки.
        connect()

        reply("""{"jsonrpc":"2.0","id":"s-1","method":"неведомое","params":{}}""")

        val answer = received()
        assertEquals(JsonPrimitive("s-1"), answer["id"], "номер сервера возвращается как есть, даже строкой")
        assertEquals(
            JsonRpcConnection.METHOD_NOT_FOUND,
            answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun `a handled server request gets its result`() {
        connect(onRequest = { method, _ ->
            if (method == "workspace/configuration") Json.parseToJsonElement("[null]") else null
        })

        reply("""{"jsonrpc":"2.0","id":7,"method":"workspace/configuration","params":{"items":[{}]}}""")

        val answer = received()
        assertEquals(7, answer["id"]!!.jsonPrimitive.int)
        assertEquals(Json.parseToJsonElement("[null]"), answer["result"])
    }

    @Test
    fun `a failing handler answers with an error and does not kill the reader`() {
        val rpc = connect(onRequest = { _, _ -> error("обработчик сломался") })

        reply("""{"jsonrpc":"2.0","id":1,"method":"что-то","params":null}""")
        val answer = received()
        assertEquals(
            JsonRpcConnection.INTERNAL_ERROR,
            answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.int,
        )

        // Читающий поток жив: обычный запрос после этого проходит.
        val call = rpc.request("после")
        val id = received()["id"]!!.jsonPrimitive.int
        reply("""{"jsonrpc":"2.0","id":$id,"result":1}""")
        assertEquals(JsonPrimitive(1), call.result.await())
    }

    @Test
    fun `notifications reach their handler`() {
        connect()

        reply("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///a.py"}}""")
        // Уведомление ответа не требует, и узнать, что оно разобрано, можно только
        // дождавшись обработчика. Ждём с потолком: повиснуть тест не должен.
        val deadline = System.currentTimeMillis() + 5_000
        while (notifications.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals("textDocument/publishDiagnostics", notifications.single().first)
    }

    @Test
    fun `a garbled message does not tear down the connection`() {
        val rpc = connect()
        val call = rpc.request("после мусора")
        val id = received()["id"]!!.jsonPrimitive.int

        reply("это не JSON")
        reply("""{"jsonrpc":"2.0","id":$id,"result":"дошло"}""")

        assertEquals(JsonPrimitive("дошло"), call.result.await())
        assertTrue(closeReasons.isEmpty(), "соединение живо: ${closeReasons.toList()}")
    }

    @Test
    fun `cancel tells the server which call to drop`() {
        val rpc = connect()
        val call = rpc.request("долгий")
        received()

        rpc.cancel(call.id)

        val cancel = received()
        assertEquals("\$/cancelRequest", cancel["method"]!!.jsonPrimitive.content)
        assertEquals(call.id, cancel["params"]!!.jsonObject["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a notification carries no id`() {
        val rpc = connect()

        rpc.notify("initialized", JsonObject(emptyMap()))

        val sent = received()
        assertNull(sent["id"], "у уведомления нет номера — иначе сервер будет ждать, что мы ждём ответа")
        assertEquals("initialized", sent["method"]!!.jsonPrimitive.content)
    }
}
