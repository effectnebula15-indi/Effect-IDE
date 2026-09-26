package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.lsp.Diagnostic
import io.github.effectnebula.eide.core.lsp.DiagnosticMarks
import io.github.effectnebula.eide.core.lsp.LspPosition
import io.github.effectnebula.eide.core.lsp.LspRange
import io.github.effectnebula.eide.core.lsp.PositionEncoding
import io.github.effectnebula.eide.core.lsp.Severity
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.DIAGNOSTIC_MESSAGE_TAG
import io.github.effectnebula.eide.ui.EditorScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ошибки на экране: волна под словом и сообщение в строке состояния.
 *
 * Волна проверяется по пикселям: красного в картинке нет больше нигде — текст
 * белый, фон чёрный, — поэтому «есть ли красное и где» отвечает на вопрос
 * «нарисована ли ошибка и под тем ли словом», не завися от шрифта.
 */
@OptIn(ExperimentalTestApi::class)
class DiagnosticsUiTest {

    private val colors = EditorColors(
        background = Color.Black,
        text = Color.White,
        gutterBackground = Color.DarkGray,
        gutterText = Color.Gray,
        currentLineGutterText = Color.White,
        selection = Color.Blue,
        caret = Color.White,
        searchMatch = Color.Green,
        diagnostics = mapOf(Severity.Error to Color.Red),
    )

    private fun editor(text: String) = EditorState(Document(Rope.of(text)), PlainBreaker)

    private fun diagnostic(
        line: Int,
        from: Int,
        to: Int,
        severity: Severity = Severity.Error,
        message: String = "ошибка",
        toLine: Int = line,
    ) = Diagnostic(LspRange(LspPosition(line, from), LspPosition(toLine, to)), severity, message, "pyflakes")

    private fun ComposeUiTest.publish(state: EditorState, marks: DiagnosticMarks, vararg diagnostics: Diagnostic) {
        runOnIdle { marks.publish(state.document.version, diagnostics.toList(), PositionEncoding.Utf16) }
        waitForIdle()
    }

    /** Где на картинке красное: границы по x и y, или null, если его нет. */
    private data class Red(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    private fun ComposeUiTest.red(): Red? {
        val pixels = onRoot().captureToImage().toPixelMap()
        var found: Red? = null
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > 0.5f && color.green < 0.3f && color.blue < 0.3f) {
                    found = found?.let { Red(minOf(it.minX, x), maxOf(it.maxX, x), minOf(it.minY, y), maxOf(it.maxY, y)) }
                        ?: Red(x, x, y, y)
                }
            }
        }
        return found
    }

    @Test
    fun `a published error is underlined, and nothing is red without it`() = runComposeUiTest {
        val state = editor("x = nope\ny = 1\n")
        val marks = DiagnosticMarks(state)
        setContent { CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), diagnostics = marks) }

        assertEquals(null, red(), "до ответа сервера красного быть не должно — иначе тест ничего не докажет")

        // Ответ приходит после кадра, без правки: перерисовка — только по ревизии пометок.
        publish(state, marks, diagnostic(0, 4, 8))

        assertTrue(red() != null, "ошибка не подчёркнута")
    }

    @Test
    fun `the wave sits under its own word and its own line`() = runComposeUiTest {
        val state = editor("x = nope\ny = 1\n")
        val marks = DiagnosticMarks(state)
        setContent { CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), diagnostics = marks) }

        publish(state, marks, diagnostic(0, 0, 1))
        val first = red()!!
        publish(state, marks, diagnostic(0, 4, 8))
        val later = red()!!
        publish(state, marks, diagnostic(1, 0, 1))
        val below = red()!!

        assertTrue(later.minX > first.maxX, "волна под «nope» должна начинаться правее «x»: $first, $later")
        assertTrue(later.maxX - later.minX > first.maxX - first.minX, "четыре буквы длиннее одной")
        assertTrue(below.minY > first.maxY, "волна второй строки должна быть ниже первой: $first, $below")

        // Ошибка до начала следующей строки захватывает перенос, но на следующей
        // строке её нет — волна там была бы под чужим словом.
        publish(state, marks, diagnostic(0, 4, 0, toLine = 1))
        assertEquals(later.maxY, red()!!.maxY, "волна переползла на строку, где ошибки нет")
    }

    @Test
    fun `an error at a point on an empty line is still visible`() = runComposeUiTest {
        val state = editor("def f():\n\nx = 1\n")
        val marks = DiagnosticMarks(state)
        setContent { CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), diagnostics = marks) }

        publish(state, marks, diagnostic(1, 0, 0))

        assertTrue(red() != null, "ошибка нулевой ширины должна подчёркиваться хотя бы на знак")
    }

    @Test
    fun `deleting the marked word takes the wave away at once`() = runComposeUiTest {
        val state = editor("x = nope\n")
        val marks = DiagnosticMarks(state)
        setContent { CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), diagnostics = marks) }
        publish(state, marks, diagnostic(0, 4, 8))

        runOnIdle { state.replaceAll(EditTransaction.delete(4, 8)) }
        waitForIdle()

        assertEquals(null, red())
    }

    @Test
    fun `a severity without a color is not underlined`() = runComposeUiTest {
        val state = editor("x = nope\n")
        val marks = DiagnosticMarks(state)
        setContent { CodeEditor(state, colors, Modifier.size(400.dp, 200.dp), diagnostics = marks) }

        publish(state, marks, diagnostic(0, 4, 8, Severity.Warning))

        assertEquals(null, red())
    }

    @Test
    fun `the status bar tells what is wrong under the caret`() = runComposeUiTest {
        val state = editor("x = nope\ny = 1\n")
        val marks = DiagnosticMarks(state)
        setContent { EditorScreen(state, Modifier.size(600.dp, 300.dp), diagnostics = marks) }
        publish(state, marks, diagnostic(0, 4, 8, message = "неизвестное имя 'nope'"))

        runOnIdle { state.setCarets(CaretSet.single(6)) }
        waitForIdle()
        onNodeWithTag(DIAGNOSTIC_MESSAGE_TAG).assertTextEquals("неизвестное имя 'nope'")
        onNodeWithText("ошибок 1").assertExists()

        runOnIdle { state.setCarets(CaretSet.single(state.text.lineStart(1))) }
        waitForIdle()
        onNodeWithTag(DIAGNOSTIC_MESSAGE_TAG).assertDoesNotExist()
        onNodeWithText("ошибок 1").assertExists()
    }

    @Test
    fun `an answer under a standing caret shows up without moving it`() = runComposeUiTest {
        // Курсор уже на слове, человек ждёт. Движения нет — пересобрать строку
        // состояния может только ответ сервера.
        val state = editor("x = nope\n")
        state.setCarets(CaretSet.single(6))
        val marks = DiagnosticMarks(state)
        setContent { EditorScreen(state, Modifier.size(600.dp, 300.dp), diagnostics = marks) }
        onNodeWithTag(DIAGNOSTIC_MESSAGE_TAG).assertDoesNotExist()

        publish(state, marks, diagnostic(0, 4, 8, message = "неизвестное имя 'nope'"))

        onNodeWithTag(DIAGNOSTIC_MESSAGE_TAG).assertTextEquals("неизвестное имя 'nope'")
    }
}
