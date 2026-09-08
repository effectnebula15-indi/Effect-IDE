package io.github.effectnebula.eide.ui.editor

import androidx.compose.ui.geometry.Offset
import io.github.effectnebula.eide.core.editor.Caret
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object PlainBreaker : GraphemeBreaker {
    override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
    override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
}

/**
 * Геометрия редактора — арифметика, в которой живут ошибки на единицу.
 *
 * Проверяется без экрана: обе функции чистые, и это ровно то, что можно
 * проверить на любой машине, в отличие от самой отрисовки.
 */
class CodeEditorGeometryTest {

    private val metrics = LineMetrics(height = 20f, digitWidth = 10f)
    private val gutter = 40f

    private fun text(vararg lines: String) = Rope.of(lines.joinToString("\n"))

    // --- попадание тапа --------------------------------------------------------

    @Test
    fun `tap picks the line under the finger`() {
        val rope = text("first", "second", "third")

        val offset = offsetAt(rope, metrics, gutter, scrollPx = 0f, position = Offset(gutter + 5f, 25f))

        assertEquals(1, rope.lineOf(offset), "палец во второй строке, а попали не туда")
    }

    @Test
    fun `tap accounts for scrolling`() {
        val rope = text("one", "two", "three", "four", "five")

        // Прокрутили на две строки: верх экрана это третья строка.
        val offset = offsetAt(rope, metrics, gutter, scrollPx = 40f, position = Offset(gutter + 5f, 5f))

        assertEquals(2, rope.lineOf(offset))
    }

    @Test
    fun `tap past the end of a line stops at its end`() {
        val rope = text("ab", "longer line")

        val offset = offsetAt(rope, metrics, gutter, scrollPx = 0f, position = Offset(gutter + 500f, 5f))

        assertEquals(2, offset, "курсор уехал за конец строки")
    }

    @Test
    fun `tap on the gutter lands at the line start`() {
        val rope = text("first", "second")

        val offset = offsetAt(rope, metrics, gutter, scrollPx = 0f, position = Offset(5f, 25f))

        assertEquals(rope.lineStart(1), offset)
    }

    @Test
    fun `tap below the last line stops at the last line`() {
        val rope = text("one", "two")

        val offset = offsetAt(rope, metrics, gutter, scrollPx = 0f, position = Offset(gutter + 5f, 5000f))

        assertEquals(1, rope.lineOf(offset), "тап в пустоту под текстом ушёл за пределы документа")
    }

    // --- прокрутка за курсором -------------------------------------------------

    private fun editorAt(offset: Int, vararg lines: String): EditorState {
        val state = EditorState(Document(text(*lines)), PlainBreaker)
        state.setCarets(CaretSet.of(listOf(Caret(offset))))
        return state
    }

    @Test
    fun `visible caret does not move the viewport`() {
        val state = editorAt(0, "one", "two", "three")

        assertEquals(0f, scrollToCaret(state, metrics, scrollPx = 0f, viewportHeight = 200))
    }

    @Test
    fun `caret above the viewport scrolls up to it`() {
        val lines = Array(50) { "line $it" }
        val state = editorAt(0, *lines)

        // Курсор на первой строке, а показываем с двадцатой.
        assertEquals(0f, scrollToCaret(state, metrics, scrollPx = 400f, viewportHeight = 200))
    }

    @Test
    fun `caret below the viewport scrolls down just enough`() {
        val lines = Array(50) { "line $it" }
        val state = editorAt(Rope.of(lines.joinToString("\n")).lineStart(30), *lines)

        val scroll = scrollToCaret(state, metrics, scrollPx = 0f, viewportHeight = 200)

        // Тридцатая строка должна оказаться у нижнего края, а не в середине:
        // прыжок к центру при каждом шаге вниз сбивает чтение.
        assertEquals(31 * metrics.height - 200, scroll)
    }

    @Test
    fun `zero viewport leaves scrolling alone`() {
        // До первой раскладки высота ещё не известна; трогать прокрутку нельзя.
        val state = editorAt(0, "one")

        assertEquals(123f, scrollToCaret(state, metrics, scrollPx = 123f, viewportHeight = 0))
    }

    @Test
    fun `scrolling to the caret never goes negative`() {
        val state = editorAt(0, "one", "two")

        val scroll = scrollToCaret(state, metrics, scrollPx = 0f, viewportHeight = 1000)

        assertTrue(scroll >= 0f, "прокрутка ушла в минус: $scroll")
    }
}
