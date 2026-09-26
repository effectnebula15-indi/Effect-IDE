package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object SimpleBreaker : GraphemeBreaker {
    override fun next(line: CharSequence, from: Int): Int = when {
        from >= line.length -> line.length
        line[from].isHighSurrogate() && from + 1 < line.length -> from + 2
        else -> from + 1
    }

    override fun previous(line: CharSequence, from: Int): Int = when {
        from <= 0 -> 0
        from >= 2 && line[from - 1].isLowSurrogate() && line[from - 2].isHighSurrogate() -> from - 2
        else -> from - 1
    }
}

class EditorStateTest {

    private fun editor(text: String, vararg caretsAt: Int): EditorState {
        val state = EditorState(Document(Rope.of(text)), SimpleBreaker)
        if (caretsAt.isNotEmpty()) {
            state.setCarets(CaretSet.of(caretsAt.map { Caret(it) }))
        }
        return state
    }

    // --- набор -----------------------------------------------------------------

    @Test
    fun `typing inserts at every caret`() {
        val state = editor("aaabbbccc", 0, 3, 6)
        state.type("-")

        assertEquals("-aaa-bbb-ccc", state.text.toString())
        assertEquals(listOf(1, 5, 9), state.carets.carets.map { it.head })
    }

    @Test
    fun `typing replaces the selection`() {
        val state = EditorState(Document(Rope.of("abcdef")), SimpleBreaker)
        state.setCarets(CaretSet.of(listOf(Caret(anchor = 1, head = 4))))

        state.type("X")

        assertEquals("aXef", state.text.toString())
        assertEquals(2, state.carets.primary.head)
    }

    @Test
    fun `newline keeps the indentation of the current line`() {
        // Иначе в коде с отступами каждая строка начинается от края, и человек
        // добивает пробелы руками — на телефоне особенно неприятно.
        val state = editor("    def f():", 12)
        state.insertNewline()

        assertEquals("    def f():\n    ", state.text.toString())
        assertEquals(17, state.carets.primary.head)
    }

    @Test
    fun `newline on a line without indentation adds none`() {
        val state = editor("abc", 3)
        state.insertNewline()
        assertEquals("abc\n", state.text.toString())
    }

    // --- удаление --------------------------------------------------------------

    @Test
    fun `backspace removes the previous character`() {
        val state = editor("abc", 3)
        state.deleteBackward()
        assertEquals("ab", state.text.toString())
        assertEquals(2, state.carets.primary.head)
    }

    @Test
    fun `backspace removes a whole surrogate pair`() {
        val state = editor("a😀", 3)
        state.deleteBackward()
        assertEquals("a", state.text.toString(), "удалилась половина суррогатной пары")
    }

    @Test
    fun `backspace at line start joins the lines`() {
        val state = editor("abc\ndef", 4)
        state.deleteBackward()
        assertEquals("abcdef", state.text.toString())
    }

    @Test
    fun `backspace removes the selection instead of one character`() {
        val state = EditorState(Document(Rope.of("abcdef")), SimpleBreaker)
        state.setCarets(CaretSet.of(listOf(Caret(anchor = 1, head = 4))))

        state.deleteBackward()

        assertEquals("aef", state.text.toString())
    }

    @Test
    fun `backspace at document start does nothing`() {
        val state = editor("abc", 0)
        state.deleteBackward()
        assertEquals("abc", state.text.toString())
        assertFalse(state.document.canUndo, "пустая правка попала в историю")
    }

    @Test
    fun `delete removes the next character`() {
        val state = editor("abc", 0)
        state.deleteForward()
        assertEquals("bc", state.text.toString())
    }

    @Test
    fun `delete at document end does nothing`() {
        val state = editor("abc", 3)
        state.deleteForward()
        assertEquals("abc", state.text.toString())
        assertFalse(state.document.canUndo)
    }

    // --- история ---------------------------------------------------------------

    @Test
    fun `undo brings the caret to the undone change`() {
        // Человек, нажавший undo, хочет увидеть, что именно откатилось.
        val state = editor("начало конец", 6)
        state.type(" вставка")
        assertEquals("начало вставка конец", state.text.toString())

        assertTrue(state.undo())

        assertEquals("начало конец", state.text.toString())
        assertEquals(6, state.carets.primary.head, "курсор остался вдали от отката")
    }

    @Test
    fun `redo returns the text`() {
        val state = editor("abc", 3)
        state.type("def")
        state.undo()

        assertTrue(state.redo())
        assertEquals("abcdef", state.text.toString())
    }

    @Test
    fun `undo on empty history is safe`() {
        val state = editor("abc", 0)
        assertFalse(state.undo())
        assertEquals("abc", state.text.toString())
    }

    // --- курсоры ---------------------------------------------------------------

    @Test
    fun `select all covers the document`() {
        val state = editor("abc\ndef")
        state.selectAll()

        assertEquals(0, state.carets.primary.start)
        assertEquals(7, state.carets.primary.end)
    }

    @Test
    fun `caret added below lands under the previous one`() {
        val state = editor("aaaa\nbbbb\ncccc", 2)
        state.addCaretBelow()

        assertEquals(listOf(2, 7), state.carets.carets.map { it.head })
    }

    @Test
    fun `caret is not added past the last line`() {
        val state = editor("only line", 3)
        state.addCaretBelow()

        assertEquals(1, state.carets.carets.size, "курсор добавился в никуда")
    }

    @Test
    fun `edits in different places do not merge into one undo`() {
        val state = editor("aaaa bbbb", 0)
        state.type("X")
        state.setCarets(CaretSet.single(9))
        state.type("Y")

        state.undo()
        assertEquals("Xaaaa bbbb", state.text.toString(), "правки в разных местах откатились вместе")
    }

    @Test
    fun `placing the caret explicitly starts a new undo action`() {
        // Тонкий случай: курсор ставится ТУДА ЖЕ, где он и был, поэтому проверка
        // соседства в Document группировку не разорвёт. Разорвать её должно само
        // намеренное действие пользователя — щелчок мышью или тап.
        //
        // Без этого теста мутация «убрать breakGrouping из setCarets» проходила
        // незамеченной: соседний тест ловил разрыв по другой причине.
        val state = editor("", 0)
        state.type("a")
        assertEquals(1, state.carets.primary.head)

        state.setCarets(CaretSet.single(1))
        state.type("b")

        state.undo()
        assertEquals("a", state.text.toString(), "щелчок не отделил новую правку от предыдущей")
    }

    @Test
    fun `typing after moving with arrows is a separate action`() {
        val state = editor("abcdef", 0)
        state.type("1")
        state.move(MoveTo.Right)
        state.move(MoveTo.Right)
        state.type("2")

        assertEquals("1ab2cdef", state.text.toString())
    }
}
