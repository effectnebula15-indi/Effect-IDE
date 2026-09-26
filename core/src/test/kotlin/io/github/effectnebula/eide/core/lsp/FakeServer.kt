package io.github.effectnebula.eide.core.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Поддельный сервер: две трубы и руки теста.
 *
 * Тест сам читает то, что пишет клиент, и сам отвечает. Так проверяется ровно
 * то, что ломается у настоящих серверов: ответы не по порядку, встречные
 * вопросы, смерть посреди работы.
 *
 * **Чтение — с таймаутом.** Первая редакция читала без него, и мутация «клиент
 * не отвечает на запрос сервера» не роняла тест, а вешала его: прогон стоял до
 * внешнего таймаута. Поломку это ловит, но в CI повисший тест съедает задачу
 * целиком, а причину из него не достать.
 */
class FakeServer : AutoCloseable {

    private val toServer = PipedOutputStream()
    private val serverIn = PipedInputStream(toServer, 1 shl 16)
    private val toClient = PipedOutputStream()

    /** Концы для клиента. */
    val clientInput = PipedInputStream(toClient, 1 shl 16)
    val clientOutput: PipedOutputStream get() = toServer

    private val reader = Executors.newSingleThreadExecutor { Thread(it, "fake-server").apply { isDaemon = true } }

    /** Следующее сообщение от клиента — или провал через пять секунд. */
    fun received(): JsonObject {
        val body = reader.submit<String?> { LspWire.readText(serverIn) }.get(5, TimeUnit.SECONDS)
        return Json.parseToJsonElement(body!!).jsonObject
    }

    /** Пропускает сообщения, пока не встретит нужный метод. */
    fun receivedMethod(method: String): JsonObject {
        repeat(20) {
            val message = received()
            if ((message["method"] as? kotlinx.serialization.json.JsonPrimitive)?.content == method) return message
        }
        error("клиент так и не прислал $method")
    }

    fun reply(json: String) = LspWire.writeText(toClient, json)

    /** Сервер «умер»: его поток к клиенту закрылся. */
    fun die() = toClient.close()

    override fun close() {
        runCatching { toClient.close() }
        runCatching { toServer.close() }
        reader.shutdownNow()
    }
}
