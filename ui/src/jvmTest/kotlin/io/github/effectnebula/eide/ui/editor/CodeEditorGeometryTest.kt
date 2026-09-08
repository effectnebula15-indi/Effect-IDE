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
import kotlin.math.roundToInt
import kotlin.test.assertTrue

/** Разбиение по кодовым единицам: тестам геометрии настоящий ICU не нужен. */
internal object PlainBreaker : GraphemeBreaker {
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

    /**
     * Колонка по ширине цифры.
     *
     * В самом редакторе колонку определяет разметка строки; здесь она подставлена
     * простой арифметикой, потому что проверяется не она, а всё вокруг неё: выбор
     * строки, учёт обеих прокруток и удержание в границах строки.
     */
    private val monospaceColumn: (Int, Float) -> Int =
        { _, x -> (x / metrics.digitWidth).roundToInt().coerceAtLeast(0) }

    // --- попадание тапа --------------------------------------------------------

    @Test
    fun `tap picks the line under the finger`() {
        val rope = text("first", "second", "third")

        val offset = offsetAt(rope, metrics, gutter, scrollPx = 0f, scrollXPx = 0f, columnAt = monospaceColumn, position = Offset(gutter + 5f, 25f))

        assertEquals(1, rope.lineOf(offset), "палец во второй строке, а попали не туда")
    }

    @Test
    fun `tap accounts for scrolling`() {
        val rope = text("one", "two", "three", "four", "five")

        // Прокрутили на две строки: верх экрана это третья строка.
        val offset = offsetAt(rope, metrics, gutter, scrollPx = 40f, scrollXPx = 0f, columnAt = monospaceColumn, position = Offset(gutter + 5f, 5f))

        assertEquals(2, rope.lineOf(offset))
    }

    @Test
    fun `tap past the end of a line stops at its end`() {
        val rope = text("ab", "longer line")

        val offset = offsetAt(rope, metrics, gutter, scrollPx = 0f, scrollXPx = 0f, columnAt = monospaceColumn, position = Offset(gutter + 500f, 5f))

        assertEquals(2, offset, "курсор уехал за конец строки")
    }

    @Test
    fun `tap on the gutter lands at the line start`() {
        val rope = text("first", "second")

        val offset = offsetAt(rope, metrics, gutter, scrollPx = 0f, scrollXPx = 0f, columnAt = monospaceColumn, position = Offset(5f, 25f))

        assertEquals(rope.lineStart(1), offset)
    }

    @Test
    fun `tap below the last line stops at the last line`() {
        val rope = text("one", "two")

        val offset = offsetAt(rope, metrics, gutter, scrollPx = 0f, scrollXPx = 0f, columnAt = monospaceColumn, position = Offset(gutter + 5f, 5000f))

        assertEquals(1, rope.lineOf(offset), "тап в пустоту под текстом ушёл за пределы документа")
    }

    @Test
    fun `tap accounts for horizontal scrolling`() {
        val rope = text("0123456789abcdef")

        // Уехали вправо на пять знаков: у левого края текста стоит шестой.
        val offset = offsetAt(
            rope, metrics, gutter,
            scrollPx = 0f, scrollXPx = 5 * metrics.digitWidth,
            position = Offset(gutter + 1f, 5f), columnAt = monospaceColumn,
        )

        assertEquals(5, offset)
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

    // --- горизонтальная прокрутка за курсором ----------------------------------

    private fun caretAt(column: Int) =
        CaretBounds(column * metrics.digitWidth, (column + 1) * metrics.digitWidth)

    @Test
    fun `visible caret does not move the viewport sideways`() {
        assertEquals(0f, scrollXToCaret(caretAt(3), scrollXPx = 0f, textWidth = 200f))
    }

    @Test
    fun `caret past the right edge scrolls just enough`() {
        val scroll = scrollXToCaret(caretAt(30), scrollXPx = 0f, textWidth = 200f)

        // Курсор в 31-й колонке должен встать у правого края, а не в середине.
        assertEquals(31 * metrics.digitWidth - 200f, scroll)
    }

    @Test
    fun `caret left of the viewport scrolls back to it`() {
        assertEquals(2 * metrics.digitWidth, scrollXToCaret(caretAt(2), scrollXPx = 500f, textWidth = 200f))
    }

    @Test
    fun `a caret at the line start resets the sideways scroll`() {
        // Переход на короткую строку не должен оставлять экран уехавшим вправо:
        // человек смотрит на пустоту и не понимает, куда делся текст.
        assertEquals(0f, scrollXToCaret(caretAt(0), scrollXPx = 500f, textWidth = 200f))
    }

    @Test
    fun `zero width leaves sideways scrolling alone`() {
        assertEquals(77f, scrollXToCaret(caretAt(0), scrollXPx = 77f, textWidth = 0f))
    }

    @Test
    fun `scrolling to the caret never goes negative`() {
        val state = editorAt(0, "one", "two")

        val scroll = scrollToCaret(state, metrics, scrollPx = 0f, viewportHeight = 1000)

        assertTrue(scroll >= 0f, "прокрутка ушла в минус: $scroll")
    }
}
