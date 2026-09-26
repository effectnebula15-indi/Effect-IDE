package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.text.Rope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Клиент LSP против поддельного сервера.
 *
 * Разбор ответов и то, что клиент сам шлёт, проверяются здесь; что это
 * работает с настоящим сервером — в `PylspTest`.
 */
class LspClientTest {

    private val server = FakeServer()
    private val diagnostics = CopyOnWriteArrayList<Published>()

    private data class Published(val uri: String, val version: Long?, val list: List<Diagnostic>)

    private val client = LspClient(
        input = server.clientInput,
        output = server.clientOutput,
        onDiagnostics = { uri, version, list -> diagnostics += Published(uri, version, list) },
    )

    @AfterTest
    fun cleanup() {
        client.close()
        server.close()
    }

    /** Проводит инициализацию и возвращает то, что клиент прислал в `initialize`. */
    private fun initialized(result: String = """{"capabilities":{}}"""): JsonObject {
        val done = client.initialize(File("/проект"), processId = 42)
        val request = server.received()
        val id = request["id"]!!.jsonPrimitive.int
        server.reply("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        done.get(5, TimeUnit.SECONDS)
        server.receivedMethod("initialized")
        return request
    }

    private fun completionReply(result: String): List<CompletionItem> {
        initialized()
        val (_, answer) = client.completion("file:///a.py", LspPosition(0, 3))
        val id = server.receivedMethod("textDocument/completion")["id"]!!.jsonPrimitive.int
        server.reply("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        return answer.get(5, TimeUnit.SECONDS)
    }

    // --- инициализация -------------------------------------------------------------

    @Test
    fun `initialize declines snippets and names the position encoding`() {
        val params = initialized()["params"]!!.jsonObject
        val capabilities = params["capabilities"]!!.jsonObject

        val snippets = capabilities["textDocument"]!!.jsonObject["completion"]!!.jsonObject["completionItem"]!!
            .jsonObject["snippetSupport"]!!.jsonPrimitive.boolean
        assertEquals(false, snippets, "шаблон `range(\${1:stop})` нам нечем развернуть")

        val encodings = capabilities["general"]!!.jsonObject["positionEncodings"]!!.jsonArray
        assertEquals(listOf("utf-16", "utf-32"), encodings.map { it.jsonPrimitive.content })
        assertEquals(42, params["processId"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the root travels as a file uri with three slashes`() {
        val rootUri = initialized()["params"]!!.jsonObject["rootUri"]!!.jsonPrimitive.content

        // `File.toURI()` дал бы `file:/проект` с одной косой — часть серверов
        // такой адрес не узнаёт. Кириллица при этом кодируется процентами.
        //
        // Кириллица в пути здесь нарочно, вопреки правилу «имена файлов в тестах
        // латиницей»: файла на диске тест не создаёт, а первая редакция
        // `toLspUri` падала именно на ней — `toPath()` под `LANG=POSIX` бросает.
        assertTrue(rootUri.startsWith("file:///"), rootUri)
        assertTrue("%D0%BF" in rootUri, "кириллица в адресе закодирована: $rootUri")
    }

    @Test
    fun `initialized goes out only after the server answered initialize`() {
        client.initialize(null)
        val request = server.received()
        assertEquals("initialize", request["method"]!!.jsonPrimitive.content)

        // До ответа на `initialize` клиент обязан молчать: сервер, получивший
        // что-то раньше ответа, вправе ответить ошибкой.
        val early = runCatching { server.received() }
        assertTrue(early.isFailure, "клиент заговорил раньше ответа: ${early.getOrNull()}")
    }

    // --- кодировка позиций ----------------------------------------------------------

    @Test
    fun `an announced encoding is obeyed`() {
        initialized("""{"capabilities":{"positionEncoding":"utf-32"}}""")

        assertEquals(PositionEncoding.Utf32, client.positionEncoding)
    }

    @Test
    fun `silence means utf-16, as the specification says`() {
        initialized("""{"capabilities":{},"serverInfo":{"name":"какой-то сервер"}}""")

        assertEquals(PositionEncoding.Utf16, client.positionEncoding)
    }

    @Test
    fun `a known offender is corrected even when silent`() {
        // pylsp молчит о кодировке и считает в code points — найдено замером
        // против настоящего сервера, см. `PylspTest`.
        initialized("""{"capabilities":{},"serverInfo":{"name":"pylsp","version":"1.15.0"}}""")

        assertEquals(PositionEncoding.Utf32, client.positionEncoding)
    }

    @Test
    fun `an announcement beats the offender table`() {
        // Когда pylsp начнёт объявлять кодировку сам, таблица не должна ему мешать.
        initialized("""{"capabilities":{"positionEncoding":"utf-16"},"serverInfo":{"name":"pylsp"}}""")

        assertEquals(PositionEncoding.Utf16, client.positionEncoding)
    }

    @Test
    fun `an unknown announced encoding falls back to utf-16`() {
        initialized("""{"capabilities":{"positionEncoding":"utf-7"}}""")

        assertEquals(PositionEncoding.Utf16, client.positionEncoding)
    }

    // --- вопросы сервера -------------------------------------------------------------

    @Test
    fun `configuration is answered with one entry per question`() {
        initialized()

        server.reply(
            """{"jsonrpc":"2.0","id":"c1","method":"workspace/configuration",
               "params":{"items":[{"section":"pylsp"},{"section":"python"},{}]}}""",
        )

        val answer = server.received()
        val result = answer["result"] as JsonArray
        // Длина ответа обязана совпасть с длиной вопроса, иначе сервер
        // сопоставит ответы не с теми пунктами.
        assertEquals(3, result.size)
        assertTrue(result.all { it == JsonNull })
    }

    @Test
    fun `progress creation is acknowledged`() {
        initialized()

        server.reply("""{"jsonrpc":"2.0","id":9,"method":"window/workDoneProgress/create","params":{"token":"t"}}""")

        val answer = server.received()
        assertEquals(9, answer["id"]!!.jsonPrimitive.int)
        assertNull(answer["error"], "на этот вопрос отвечают результатом, а не ошибкой")
    }

    // --- документ --------------------------------------------------------------------

    @Test
    fun `document versions are the caller's, as given`() {
        // Версия документа, а не счётчик клиента: по ней потом узнаётся, к какому
        // тексту относятся ошибки. Пропуски — норма: версия растёт и на движении
        // курсора по откатам, а серверу шлётся не каждая.
        initialized()
        client.didOpen("file:///a.py", "python", "x = 1", version = 7)
        client.didChange("file:///a.py", "x = 2", version = 12)

        val open = server.receivedMethod("textDocument/didOpen")
        val change = server.receivedMethod("textDocument/didChange")

        fun version(message: JsonObject) =
            message["params"]!!.jsonObject["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.long

        assertEquals(listOf(7L, 12L), listOf(version(open), version(change)))
    }

    @Test
    fun `a change carries the whole text and no range`() {
        // Событие без `range` означает «заменить всё» при любом режиме сервера —
        // поэтому полная синхронизация корректна всегда.
        initialized()
        client.didOpen("file:///a.py", "python", "x = 1", version = 0)
        client.didChange("file:///a.py", "привет = 2", version = 1)

        val change = server.receivedMethod("textDocument/didChange")["params"]!!.jsonObject
            .getValue("contentChanges").jsonArray.single().jsonObject

        assertEquals("привет = 2", change["text"]!!.jsonPrimitive.content)
        assertNull(change["range"])
    }

    // --- автодополнение --------------------------------------------------------------

    @Test
    fun `a plain array of completions is read`() {
        val items = completionReply("""[{"label":"path"},{"label":"pathsep"}]""")

        assertEquals(listOf("path", "pathsep"), items.map { it.label })
    }

    @Test
    fun `a completion list object is read`() {
        val items = completionReply("""{"isIncomplete":false,"items":[{"label":"path","detail":"модуль"}]}""")

        assertEquals("path", items.single().label)
        assertEquals("модуль", items.single().detail)
    }

    @Test
    fun `no completions is an empty list, not a failure`() {
        assertEquals(emptyList(), completionReply("null"))
    }

    @Test
    fun `the text edit wins over insert text, which wins over the label`() {
        // Сервер вправе показывать одно, а вставлять другое: «path» в списке и
        // «os.path» в тексте. Вставить подпись вместо правки — значит вставить не то.
        val items = completionReply(
            """[
                {"label":"a","insertText":"вставка","textEdit":{"newText":"правка",
                 "range":{"start":{"line":0,"character":0},"end":{"line":0,"character":3}}}},
                {"label":"b","insertText":"вставка"},
                {"label":"c"}
            ]""",
        )

        assertEquals(listOf("правка", "вставка", "c"), items.map { it.insertText })
        assertEquals(LspRange(LspPosition(0, 0), LspPosition(0, 3)), items[0].replaces)
    }

    // --- диагностика -----------------------------------------------------------------

    @Test
    fun `diagnostics arrive with their severity`() {
        initialized()

        server.reply(
            """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{
                "uri":"file:///a.py","diagnostics":[
                  {"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":4}},
                   "severity":1,"message":"неизвестное имя","source":"pyflakes"},
                  {"range":{"start":{"line":2,"character":0},"end":{"line":2,"character":1}},
                   "message":"без серьёзности"}
                ]}}""",
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (diagnostics.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        val (uri, version, list) = diagnostics.single()
        assertEquals("file:///a.py", uri)
        assertNull(version, "сервер версию не назвал — значит, её нет")
        assertEquals(Severity.Error, list[0].severity)
        assertEquals("pyflakes", list[0].source)
        // Без указания серьёзности — не ошибка: красное на подсказке пугает зря.
        assertEquals(Severity.Information, list[1].severity)
    }

    @Test
    fun `diagnostics carry the version they were computed for`() {
        initialized()

        server.reply(
            """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{
                "uri":"file:///a.py","version":42,"diagnostics":[]}}""",
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (diagnostics.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals(42L, diagnostics.single().version)
        assertTrue(diagnostics.single().list.isEmpty(), "пустой список — тоже ответ: ошибок больше нет")
    }

    @Test
    fun `the client asks for versioned diagnostics`() {
        val capabilities = initialized()["params"]!!.jsonObject["capabilities"]!!.jsonObject
        val publish = capabilities["textDocument"]!!.jsonObject["publishDiagnostics"]!!.jsonObject

        assertEquals(true, publish["versionSupport"]!!.jsonPrimitive.boolean)
    }

    // --- позиции ---------------------------------------------------------------------

    @Test
    fun `positions count utf-16 units, so an emoji takes two`() {
        // Эмодзи вне базовой плоскости — суррогатная пара, два code unit'а.
        // LSP считает так же, поэтому перевод прямой.
        val text = Rope.of("x = \"😀\"\nимя = 1")

        assertEquals(LspPosition(0, 7), text.toLspPosition(7))
        assertEquals(LspPosition(1, 3), text.toLspPosition(text.lineStart(1) + 3))
    }

    @Test
    fun `a position past the end of its line stays on that line`() {
        // Сервер видел текст чуть раньше нас; его столбец может оказаться за
        // концом строки. Вставка не должна уехать на следующую строку.
        val text = Rope.of("ab\ncd")

        assertEquals(2, text.offsetOf(LspPosition(0, 99)))
        // Строка за концом файла — это последняя строка, и столбец режется уже
        // по ней. То же правило, что у `Workspace.openAt`: одно на весь проект.
        // Первая редакция теста ждала здесь конец файла; ошибка была в ожидании.
        assertEquals(3, text.offsetOf(LspPosition(99, 0)))
        assertEquals(text.length, text.offsetOf(LspPosition(99, 99)))
    }

    @Test
    fun `in utf-32 an emoji is one character`() {
        val text = Rope.of("x = \"\uD83D\uDE00\uD83D\uDE00\" + зн")
        val afterEmoji = text.substring(0, text.length).indexOf("+")

        // Два эмодзи — четыре code unit'а, но два знака: столбец на два меньше.
        assertEquals(afterEmoji - 2, text.toLspPosition(afterEmoji, PositionEncoding.Utf32).character)
        assertEquals(afterEmoji, text.offsetOf(LspPosition(0, afterEmoji - 2), PositionEncoding.Utf32))
    }

    @Test
    fun `utf-32 positions round-trip and never split a surrogate pair`() {
        val text = Rope.of("\uD83D\uDE00а\uD83D\uDE00\nб")
        val boundaries = listOf(0, 2, 3, 5, 6, 7)

        for (offset in boundaries) {
            val position = text.toLspPosition(offset, PositionEncoding.Utf32)
            assertEquals(offset, text.offsetOf(position, PositionEncoding.Utf32), "офсет $offset")
        }
    }

    @Test
    fun `a utf-32 column past the end of its line stays on that line`() {
        val text = Rope.of("\uD83D\uDE00\nб")

        assertEquals(2, text.offsetOf(LspPosition(0, 99), PositionEncoding.Utf32))
    }

    @Test
    fun `position and offset convert back and forth`() {
        val text = Rope.of("первая\nвторая строка\n\nчетвёртая")
        for (offset in 0..text.length) {
            assertEquals(offset, text.offsetOf(text.toLspPosition(offset)), "офсет $offset")
        }
    }
}
