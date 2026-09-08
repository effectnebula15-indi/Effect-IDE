package io.github.effectnebula.eide.ui.editor

import io.github.effectnebula.eide.core.syntax.LineHighlight
import io.github.effectnebula.eide.core.syntax.PythonHighlighter
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Кэш состояний лексера.
 *
 * Ошибка здесь выглядит как «половина файла вдруг стала строкой» — и появляется
 * не сразу, а после правки выше по тексту. Проверяется именно это: что после
 * правки состояния ниже пересчитываются.
 */
class LineStatesTest {

    private fun document(vararg lines: String) = Document(Rope.of(lines.joinToString("\n")))

    private fun states(document: Document, upTo: Int): List<Int> {
        val cache = LineStates(PythonHighlighter)
        return (0..upTo).map { cache.stateBefore(document, it) }
    }

    @Test
    fun `plain code carries no state`() {
        val document = document("x = 1", "y = 2", "z = 3")

        assertEquals(listOf(0, 0, 0), states(document, 2))
    }

    @Test
    fun `an open docstring carries state down the file`() {
        val document = document("\"\"\"начало", "внутри", "внутри тоже", "конец\"\"\"", "код = 1")
        val computed = states(document, 4)

        assertEquals(LineHighlight.STATE_INITIAL, computed[0])
        assertTrue(computed[1] != LineHighlight.STATE_INITIAL, "состояние не дошло до второй строки")
        assertTrue(computed[3] != LineHighlight.STATE_INITIAL)
        assertEquals(LineHighlight.STATE_INITIAL, computed[4], "состояние не сбросилось после закрытия")
    }

    @Test
    fun `states survive repeated reads`() {
        // Кэш обязан отдавать то же самое: иначе подсветка мигает при прокрутке.
        val document = document("\"\"\"a", "b", "c")
        val cache = LineStates(PythonHighlighter)

        val first = (0..2).map { cache.stateBefore(document, it) }
        val second = (0..2).map { cache.stateBefore(document, it) }

        assertEquals(first, second)
    }

    @Test
    fun `an edit above invalidates the states below`() {
        // Это и есть та ошибка, ради которой кэш вообще проверяется: открыли
        // докстринг наверху — всё ниже стало строкой.
        val document = document("код = 1", "ещё = 2", "и = 3")
        val cache = LineStates(PythonHighlighter)

        assertEquals(LineHighlight.STATE_INITIAL, cache.stateBefore(document, 2))

        document.apply(EditTransaction.insert(0, "\"\"\"\n"))

        assertTrue(
            cache.stateBefore(document, 2) != LineHighlight.STATE_INITIAL,
            "состояние ниже правки не пересчиталось",
        )
    }

    @Test
    fun `closing a docstring clears the state below`() {
        val document = Document(Rope.of("\"\"\"\nвнутри\nещё"))
        val cache = LineStates(PythonHighlighter)

        assertTrue(cache.stateBefore(document, 2) != LineHighlight.STATE_INITIAL)

        // Закрываем докстринг в первой строке.
        document.apply(EditTransaction.insert(3, "\"\"\""))

        assertEquals(
            LineHighlight.STATE_INITIAL,
            cache.stateBefore(document, 2),
            "состояние осталось от закрытого докстринга",
        )
    }

    @Test
    fun `several edits between reads are handled`() {
        // Версия прыгает больше чем на единицу: место самой ранней правки уже
        // не узнать, и пересчитать надо всё.
        val document = document("a = 1", "b = 2", "c = 3")
        val cache = LineStates(PythonHighlighter)
        cache.stateBefore(document, 2)

        document.apply(EditTransaction.insert(document.text.length, "\nd = 4"))
        document.apply(EditTransaction.insert(0, "\"\"\"\n"))

        assertTrue(
            cache.stateBefore(document, 3) != LineHighlight.STATE_INITIAL,
            "две правки подряд оставили кэш несогласованным",
        )
    }

    @Test
    fun `asking beyond the end of the file does not crash`() {
        val document = document("x = 1")

        assertEquals(LineHighlight.STATE_INITIAL, LineStates(PythonHighlighter).stateBefore(document, 500))
    }

    @Test
    fun `the first line always starts clean`() {
        val document = document("\"\"\"докстринг", "внутри")

        assertEquals(LineHighlight.STATE_INITIAL, LineStates(PythonHighlighter).stateBefore(document, 0))
    }
}
