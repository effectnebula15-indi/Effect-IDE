package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Сессия против поддельного сервера: порядок сообщений, версии, путь ошибок.
 *
 * Поток интерфейса здесь — очередь, которую тест разбирает сам ([pumpUi]).
 * Так видно, что ошибки доходят до пометок только через него, а не прямо из
 * читающего потока, где `EditorState` трогать нельзя.
 */
class LspSessionTest {

    private val server = FakeServer()
    private val uiTasks = LinkedBlockingQueue<Runnable>()

    /** Пауза отправки; тест, которому таймер мешает, ставит её длинной до первого обращения. */
    private var pauseMs = PAUSE_MS
    private val sessionHolder = lazy {
        LspSession(
            input = server.clientInput,
            output = server.clientOutput,
            ui = { uiTasks.put(it) },
            syncPauseMs = pauseMs,
        )
    }
    private val session by sessionHolder
    private val file = File("/проект/main.py")

    @AfterTest
    fun cleanup() {
        if (sessionHolder.isInitialized()) session.close()
        server.close()
    }

    /** Выполняет одну задачу «потока интерфейса»; false — задач не было. */
    private fun pumpUi(timeoutMs: Long = 5_000): Boolean {
        val task = uiTasks.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return false
        task.run()
        return true
    }

    private fun initialize() {
        session.initialize(File("/проект"), processId = 1)
        val request = server.receivedMethod("initialize")
        server.reply("""{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.int},"result":{"capabilities":{}}}""")
        server.receivedMethod("initialized")
    }

    private fun JsonObject.params() = this["params"]!!.jsonObject
    private fun JsonObject.textDocument() = params()["textDocument"]!!.jsonObject
    private fun JsonObject.method() = this["method"]?.jsonPrimitive?.content

    private fun EditorState.typeAt(offset: Int, text: String) {
        setCarets(CaretSet.single(offset))
        type(text)
    }

    private fun publish(uri: String, version: Long?, line: Int, from: Int, to: Int) {
        val versionField = if (version == null) "" else """"version":$version,"""
        server.reply(
            """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{
                "uri":"$uri",$versionField"diagnostics":[
                  {"range":{"start":{"line":$line,"character":$from},"end":{"line":$line,"character":$to}},
                   "severity":1,"message":"неизвестное имя"}]}}""",
        )
    }

    @Test
    fun `a file opens with its document version and whole text`() {
        initialize()
        val state = testEditor("x = 1\n")
        state.typeAt(0, "ab")

        session.open(file, "python", state)

        val open = server.receivedMethod("textDocument/didOpen").textDocument()
        assertEquals(state.document.version, open["version"]!!.jsonPrimitive.long)
        assertEquals("abx = 1\n", open["text"]!!.jsonPrimitive.content)
        assertEquals(file.toLspUri(), open["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `quick edits reach the server once, after the pause`() {
        initialize()
        val state = testEditor("x = 1\n")
        session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")

        for (letter in "abc") state.type(letter.toString())
        // Таймер паузы не трогает редактор сам — он просит поток интерфейса.
        assertTrue(pumpUi(), "после паузы отправка должна попроситься в поток интерфейса")
        session.close(file)

        val change = server.received()
        assertEquals("textDocument/didChange", change.method())
        assertEquals(state.document.version, change.textDocument()["version"]!!.jsonPrimitive.long)
        val text = change.params()["contentChanges"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("abcx = 1\n", text)
        assertEquals("textDocument/didClose", server.received().method(), "три буквы — одна отправка")
    }

    @Test
    fun `steady typing waits for a pause instead of sending every so often`() {
        // Буквы идут чаще паузы, а всё вместе дольше неё. Пауза — одна отправка
        // в конце; «раз в столько-то» — несколько по дороге.
        initialize()
        val state = testEditor("x = 1\n")
        session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")

        repeat(6) {
            state.type("a")
            Thread.sleep(PAUSE_MS / 4)
            // Поток интерфейса не спит, пока печатают: всё, о чём попросил таймер,
            // выполняется сразу. Без этого задачи копились бы до конца набора и
            // первая же видела бы весь текст — тест не отличил бы паузу от периода.
            while (pumpUi(timeoutMs = 0)) Unit
        }
        while (pumpUi(timeoutMs = PAUSE_MS * 3)) Unit
        session.close(file)

        val methods = generateSequence { server.received().method() }
            .takeWhile { it != "textDocument/didClose" }
            .toList()
        assertEquals(listOf("textDocument/didChange"), methods)
    }

    @Test
    fun `closing releases work waiting for an initialization that never comes`() {
        // Первая редакция закрывалась через shutdownNow: задачи, не успевшие
        // начаться, выбрасывались, и их future не завершались никогда.
        val state = testEditor("x\n")
        session.open(file, "python", state)
        val completion = session.completion(file, 0)
        val goodbye = session.shutdown()

        session.close()

        goodbye.get(5, TimeUnit.SECONDS)
        assertIs<ConnectionClosed>(assertFailsWith<ExecutionException> { completion.get(5, TimeUnit.SECONDS) }.cause)
    }

    @Test
    fun `a closed session fails requests instead of throwing into the ui`() {
        val state = testEditor("x\n")
        session.open(file, "python", state)
        session.close()

        state.type("y") // таймер паузы в закрытой сессии — не исключение в потоке интерфейса
        val completion = session.completion(file, 0)

        assertFailsWith<ExecutionException> { completion.get(5, TimeUnit.SECONDS) }
        session.shutdown().get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `moving the caret sends nothing`() {
        initialize()
        val state = testEditor("x = 1\n")
        session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")

        state.setCarets(CaretSet.single(3))

        assertFalse(pumpUi(timeoutMs = PAUSE_MS * 3), "текст не менялся — отправлять нечего")
    }

    @Test
    fun `completion sends the fresh text before asking`() {
        initialize()
        val state = testEditor("import os\n")
        session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")
        state.typeAt(state.text.length, "os.")

        session.completion(file, state.carets.primary.head)

        val change = server.received()
        assertEquals("textDocument/didChange", change.method(), "запрос о тексте, которого сервер не видел")
        val request = server.received()
        assertEquals("textDocument/completion", request.method())
        val position = request.params()["position"]!!.jsonObject
        assertEquals(1, position["line"]!!.jsonPrimitive.int)
        assertEquals(3, position["character"]!!.jsonPrimitive.int)
    }

    @Test
    fun `completion without edits asks right away`() {
        // Та же версия второй раз — нарушение протокола: версии обязаны расти.
        initialize()
        val state = testEditor("import os\n")
        session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")

        session.completion(file, 3)

        assertEquals("textDocument/completion", server.received().method())
    }

    @Test
    fun `an edit pending when the file closes is never sent`() {
        // didChange после didClose — изменение файла, которого у сервера нет.
        initialize()
        val state = testEditor("x\n")
        session.open(file, "python", state)
        state.type("y")
        session.close(file)
        assertTrue(pumpUi(), "таймер паузы сработает и после закрытия — важно, что он сделает")
        session.open(File("/проект/other.py"), "python", testEditor("z\n"))

        val methods = List(3) { server.received().method() }
        assertEquals(listOf("textDocument/didOpen", "textDocument/didClose", "textDocument/didOpen"), methods)
    }

    @Test
    fun `everything asked before the server is ready keeps its order`() {
        val state = testEditor("x\n")
        session.initialize(File("/проект"), processId = 1)
        session.open(file, "python", state)
        state.typeAt(1, " = os.")
        session.completion(file, state.carets.primary.head)

        val request = server.receivedMethod("initialize")
        server.reply("""{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.int},"result":{"capabilities":{}}}""")

        val methods = List(4) { server.received().method() }
        assertEquals(
            listOf("initialized", "textDocument/didOpen", "textDocument/didChange", "textDocument/completion"),
            methods,
        )
    }

    @Test
    fun `diagnostics reach the marks of their file only through the ui thread`() {
        initialize()
        val state = testEditor("x = nope\n")
        val marks = session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")

        publish(file.toLspUri(), state.document.version, 0, 4, 8)

        assertTrue(marks.marks.isEmpty(), "до потока интерфейса пометки трогать нельзя")
        assertTrue(pumpUi())
        assertEquals("nope", marks.marks.single().let { state.text.substring(it.start, it.end) })
    }

    @Test
    fun `a differently spelled address still finds its file`() {
        // Кириллица в пути: сервер вправе вернуть проценты в нижнем регистре.
        initialize()
        val state = testEditor("x = nope\n")
        val marks = session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")
        val respelled = file.toLspUri().replace(Regex("%[0-9A-F]{2}")) { it.value.lowercase() }
        assertTrue(respelled != file.toLspUri(), "проверка бессмысленна, если запись не изменилась")

        publish(respelled, state.document.version, 0, 4, 8)
        assertTrue(pumpUi())

        assertEquals(1, marks.marks.size)
    }

    @Test
    fun `diagnostics without a version belong to the last text sent`() {
        // Таймер паузы отправил бы новый текст раньше ответа — и ответ без версии
        // приписался бы ему. Здесь проверяется именно запоздавший ответ.
        pauseMs = TimeUnit.MINUTES.toMillis(1)
        initialize()
        val state = testEditor("x = nope\n")
        val marks = session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")

        publish(file.toLspUri(), version = null, 0, 4, 8)
        assertTrue(pumpUi())
        assertEquals(1, marks.marks.size)

        // Допечатали, а сервер ответил без версии про старый текст — выбросить.
        state.typeAt(0, "#")
        publish(file.toLspUri(), version = null, 0, 0, 1)
        assertTrue(pumpUi())
        assertEquals("nope", marks.marks.single().let { state.text.substring(it.start, it.end) })
    }

    @Test
    fun `a closed file says didClose and hears nothing more`() {
        initialize()
        val state = testEditor("x = nope\n")
        val marks = session.open(file, "python", state)
        server.receivedMethod("textDocument/didOpen")

        session.close(file)
        assertEquals("textDocument/didClose", server.received().method())

        publish(file.toLspUri(), state.document.version, 0, 4, 8)
        while (pumpUi(timeoutMs = 500)) Unit
        assertTrue(marks.marks.isEmpty())

        state.typeAt(0, "#")
        assertFalse(pumpUi(timeoutMs = PAUSE_MS * 3), "закрытый файл не должен отправляться")
    }

    @Test
    fun `opening the same editor twice sends one didOpen`() {
        initialize()
        val state = testEditor("x\n")
        val first = session.open(file, "python", state)
        val second = session.open(file, "python", state)
        session.close(file)

        assertTrue(first === second)
        assertEquals("textDocument/didOpen", server.received().method())
        assertEquals("textDocument/didClose", server.received().method())
    }

    @Test
    fun `a file opened before initialization waits for it`() {
        val state = testEditor("x\n")
        session.open(file, "python", state)
        session.initialize(File("/проект"), processId = 1)

        val request = server.receivedMethod("initialize")
        server.reply("""{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.int},"result":{"capabilities":{}}}""")

        assertEquals("initialized", server.received().method())
        assertEquals("textDocument/didOpen", server.received().method())
    }

    @Test
    fun `completion for a file that is not open is empty`() {
        initialize()

        val items = session.completion(file, 0).get(5, TimeUnit.SECONDS)

        assertTrue(items.isEmpty())
    }

    private companion object {
        // Короче — и медленная машина CI успеет «заснуть» между двумя буквами
        // одного слова, отправив его по частям.
        const val PAUSE_MS = 200L
    }
}
