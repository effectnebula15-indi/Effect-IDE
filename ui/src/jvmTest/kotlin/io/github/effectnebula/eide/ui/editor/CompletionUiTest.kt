package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.lsp.CompletionItem
import io.github.effectnebula.eide.core.lsp.PositionEncoding
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Автодополнение в редакторе.
 *
 * Источник здесь поддельный: он отвечает тем, что тест велел, и запоминает, о
 * чём его спросили. Настоящий сервер проверен в `:core` (`PylspTest`); здесь —
 * что ответ доходит до экрана и что выбор вставляет то, что надо, и туда.
 */
@OptIn(ExperimentalTestApi::class)
class CompletionUiTest {

    private val colors = EditorColors(
        background = Color.Black,
        text = Color.White,
        gutterBackground = Color.DarkGray,
        gutterText = Color.Gray,
        currentLineGutterText = Color.White,
        selection = Color.Blue,
        caret = Color.White,
        searchMatch = Color.Green,
    )

    private class FakeSource(vararg labels: String) : CompletionSource {
        val items = labels.map { CompletionItem(it, it, detail = null, replaces = null) }
        var asked = 0
        var pending: CompletableFuture<List<CompletionItem>>? = null
        var answerLater = false

        override val encoding = PositionEncoding.Utf16
        override fun complete(text: Rope, caret: Int): CompletableFuture<List<CompletionItem>> {
            asked++
            if (!answerLater) return CompletableFuture.completedFuture(items)
            return CompletableFuture<List<CompletionItem>>().also { pending = it }
        }
    }

    private fun editor(text: String) = EditorState(Document(Rope.of(text)), PlainBreaker).apply {
        setCarets(CaretSet.single(text.length))
    }

    private fun EditorState.content() = text.substring(0, text.length)

    private fun androidx.compose.ui.test.ComposeUiTest.ctrlSpace() = onRoot().performKeyInput {
        keyDown(Key.CtrlLeft)
        pressKey(Key.Spacebar)
        keyUp(Key.CtrlLeft)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.press(key: Key) = onRoot().performKeyInput { pressKey(key) }

    @Test
    fun `ctrl space shows the list and enter inserts the choice in place of the prefix`() = runComposeUiTest {
        val state = editor("os.pa")
        val source = FakeSource("path", "pathsep")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        onNodeWithText("path").assertExists()

        press(Key.Enter)
        waitForIdle()

        // Набранное «pa» заменено, а не дописано: не «os.papath».
        assertEquals("os.path", state.content())
        assertEquals(state.text.length, state.carets.primary.head, "курсор — за вставленным")
        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()
    }

    @Test
    fun `arrows pick another variant and tab takes it`() = runComposeUiTest {
        val state = editor("os.pa")
        val source = FakeSource("path", "pathsep")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        press(Key.DirectionDown)
        press(Key.Tab)
        waitForIdle()

        assertEquals("os.pathsep", state.content())
    }

    @Test
    fun `escape closes the list and enter then breaks the line as usual`() = runComposeUiTest {
        val state = editor("os.pa")
        val source = FakeSource("path")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        press(Key.Escape)
        waitForIdle()
        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()

        press(Key.Enter)
        waitForIdle()
        assertEquals("os.pa\n", state.content(), "Enter после Esc — обычный перевод строки")
    }

    @Test
    fun `typing further narrows the list and a mismatch closes it`() = runComposeUiTest {
        val state = editor("os.p")
        val source = FakeSource("path", "pardir")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        onNodeWithText("pardir").assertExists()

        state.type("at")
        waitForIdle()
        onNodeWithText("pardir").assertDoesNotExist()
        onNodeWithText("path").assertExists()
        assertEquals(1, source.asked, "дописанное доотбирается на месте, сервер не переспрашивается")

        state.type("z")
        waitForIdle()
        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()
    }

    @Test
    fun `a typed dot asks by itself`() = runComposeUiTest {
        // После `os.` человек ждёт именно список, а на телефоне Ctrl нет вовсе.
        val state = editor("os")
        val source = FakeSource("path")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        state.type(".")
        waitForIdle()

        assertEquals(1, source.asked)
        onNodeWithText("path").assertExists()
    }

    @Test
    fun `moving onto a dot does not ask`() = runComposeUiTest {
        // Вызывает только напечатанная точка. Иначе список выскакивал бы от
        // каждого щелчка мышью после точки.
        val state = editor("os.path")
        val source = FakeSource("path")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        state.setCarets(CaretSet.single(3))
        waitForIdle()

        assertEquals(0, source.asked)
    }

    @Test
    fun `a late answer for a place the caret has left is dropped`() = runComposeUiTest {
        // Запрос ушёл, человек ушёл на другую строку; ответ, пришедший после,
        // относится к месту, где курсора уже нет.
        val state = editor("os.pa\nx")
        state.setCarets(CaretSet.single(5))
        val source = FakeSource("path").apply { answerLater = true }
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        state.setCarets(CaretSet.single(state.text.length))
        waitForIdle()

        source.pending!!.complete(source.items)
        waitForIdle()

        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()
    }

    @Test
    fun `an empty answer opens nothing`() = runComposeUiTest {
        val state = editor("os.pa")
        val source = FakeSource()
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()

        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()
    }

    @Test
    fun `a click on a variant inserts it`() = runComposeUiTest {
        val state = editor("os.pa")
        val source = FakeSource("path", "pathsep")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        onNodeWithText("pathsep").performClick()
        waitForIdle()

        assertEquals("os.pathsep", state.content())
    }

    @Test
    fun `without a source ctrl space does nothing`() = runComposeUiTest {
        val state = editor("os.pa")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp)) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()

        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()
        assertEquals("os.pa", state.content())
    }

    @Test
    fun `an answer that arrives after escape stays closed`() = runComposeUiTest {
        // Курсор не двигался, строка та же — отбросить ответ здесь может только
        // поколение запроса. Первая редакция проверяла опоздание одним случаем,
        // где срабатывали обе защиты сразу, и мутации каждую по отдельности
        // пережили.
        val state = editor("os.pa")
        val source = FakeSource("path").apply { answerLater = true }
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        press(Key.Escape)
        waitForIdle()

        source.pending!!.complete(source.items)
        waitForIdle()

        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()
    }

    @Test
    fun `an answer for another line stays closed even if the prefix fits`() = runComposeUiTest {
        // Нового запроса не было, поколение прежнее, а префикс на новой строке
        // тоже «pa» — отбросить ответ может только проверка строки. Варианты для
        // `os.pa` на верхнем уровне другой строки — чужие варианты.
        val state = editor("os.pa\npa")
        state.setCarets(CaretSet.single(5))
        val source = FakeSource("path").apply { answerLater = true }
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        state.setCarets(CaretSet.single(state.text.length))
        waitForIdle()

        source.pending!!.complete(source.items)
        waitForIdle()

        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()
    }

    @Test
    fun `up from the first variant wraps to the last`() = runComposeUiTest {
        val state = editor("os.pa")
        val source = FakeSource("path", "pardir", "pathsep")
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        press(Key.DirectionUp)
        press(Key.Enter)
        waitForIdle()

        assertEquals("os.pathsep", state.content())
    }

    @Test
    fun `the caret lands after the insertion even when the server range runs past it`() = runComposeUiTest {
        // Сервер вправе заменить и хвост справа от курсора (`pa|th` → `pathsep`).
        // Курсор внутри заменённого участка правка сама могла бы оставить где
        // угодно; он обязан встать за вставленным.
        val state = editor("os.path")
        state.setCarets(CaretSet.single(5))
        val range = io.github.effectnebula.eide.core.lsp.LspRange(
            io.github.effectnebula.eide.core.lsp.LspPosition(0, 3),
            io.github.effectnebula.eide.core.lsp.LspPosition(0, 7),
        )
        val source = object : CompletionSource {
            override val encoding = PositionEncoding.Utf16
            override fun complete(text: Rope, caret: Int) = CompletableFuture.completedFuture(
                listOf(CompletionItem("pathsep", "pathsep", detail = null, replaces = range)),
            )
        }
        setContent { CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source) }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        press(Key.Enter)
        waitForIdle()

        assertEquals("os.pathsep", state.content())
        assertEquals(10, state.carets.primary.head)
    }


    @Test
    fun `after a failing source escape is no longer the list's`() = runComposeUiTest {
        // Сбой сервера — не повод для окна с ошибкой: автодополнение — подсказка.
        // Но ожидание обязано сняться: иначе Esc так и остаётся за списком,
        // которого нет, и до внешних обработчиков не доходит.
        //
        // Первая редакция этого теста проверяла Enter — а Enter проходит в любом
        // случае, от ожидания зависит только Esc. Мутация «сбой не снимает
        // ожидание» тест пережила. Теперь Esc ловится снаружи редактора: дошёл —
        // значит список его не забрал.
        val state = editor("os.pa")
        val source = FakeSource("path").apply { answerLater = true }
        var escapeReachedOutside = false
        setContent {
            Box(Modifier.onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) escapeReachedOutside = true
                false
            }) {
                CodeEditor(state, colors, Modifier.size(500.dp, 300.dp), completion = source)
            }
        }
        waitForIdle()

        ctrlSpace()
        waitForIdle()
        source.pending!!.completeExceptionally(IllegalStateException("сервер упал"))
        waitForIdle()
        onNodeWithTag(COMPLETION_TAG).assertDoesNotExist()

        press(Key.Escape)
        waitForIdle()

        assertTrue(escapeReachedOutside, "Esc после сбоя забрал список, которого нет")
    }

}
