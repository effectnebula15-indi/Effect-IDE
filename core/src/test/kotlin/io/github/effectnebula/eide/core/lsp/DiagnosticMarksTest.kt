package io.github.effectnebula.eide.core.lsp

import io.github.effectnebula.eide.core.editor.Caret
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.EditKind
import io.github.effectnebula.eide.core.text.EditTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Пометки ошибок: где встают и как едут за правками.
 *
 * Ошибка здесь выглядит как подчёркивание не того слова — и появляется не сразу,
 * а после правки выше по тексту или после отката. Поэтому почти каждый тест —
 * это «поставили пометку, поправили текст, проверили, где она теперь».
 */
class DiagnosticMarksTest {

    private fun editor(text: String): Pair<EditorState, DiagnosticMarks> {
        val state = testEditor(text)
        return state to DiagnosticMarks(state)
    }

    private fun diagnostic(
        line: Int,
        from: Int,
        to: Int,
        severity: Severity = Severity.Error,
        message: String = "ошибка",
        toLine: Int = line,
    ) = Diagnostic(LspRange(LspPosition(line, from), LspPosition(toLine, to)), severity, message, "pyflakes")

    private fun DiagnosticMarks.spans() = marks.map { it.start to it.end }

    private fun EditorState.typeAt(offset: Int, text: String) {
        setCarets(CaretSet.single(offset))
        type(text)
    }

    @Test
    fun `an answer for the current version lands on its words`() {
        val (state, marks) = editor("import os\nx = nope\n")

        val accepted = marks.publish(state.document.version, listOf(diagnostic(1, 4, 8)), PositionEncoding.Utf16)

        assertTrue(accepted)
        val mark = marks.marks.single()
        assertEquals("nope", state.text.substring(mark.start, mark.end))
    }

    @Test
    fun `an answer for an older version is thrown away and the old marks stay`() {
        val (state, marks) = editor("x = nope\n")
        marks.publish(state.document.version, listOf(diagnostic(0, 4, 8)), PositionEncoding.Utf16)
        val stale = state.document.version
        state.typeAt(0, "#")

        // Сервер отвечает про текст без «#»: его позиции уже чужие.
        val accepted = marks.publish(stale, listOf(diagnostic(0, 0, 1)), PositionEncoding.Utf16)

        assertFalse(accepted)
        assertEquals("nope", marks.marks.single().let { state.text.substring(it.start, it.end) })
    }

    @Test
    fun `utf-32 columns count an emoji as one`() {
        // pylsp считает кодовые точки: эмодзи — один столбец, а в строке Kotlin — два char.
        val (state, marks) = editor("s = \"😀\" + nope\n")

        marks.publish(state.document.version, listOf(diagnostic(0, 10, 14)), PositionEncoding.Utf32)

        assertEquals("nope", marks.marks.single().let { state.text.substring(it.start, it.end) })
    }

    @Test
    fun `typing before a mark moves it, typing after leaves it`() {
        val (state, marks) = editor("x = nope\ny = 1\n")
        marks.publish(state.document.version, listOf(diagnostic(0, 4, 8)), PositionEncoding.Utf16)

        state.typeAt(0, "abc")
        assertEquals(listOf(7 to 11), marks.spans())

        state.typeAt(state.text.length, "z")
        assertEquals(listOf(7 to 11), marks.spans())
    }

    @Test
    fun `typing right before a mark does not stretch it`() {
        val (state, marks) = editor("x = nope\n")
        marks.publish(state.document.version, listOf(diagnostic(0, 4, 8)), PositionEncoding.Utf16)

        state.typeAt(4, "(")

        assertEquals("nope", marks.marks.single().let { state.text.substring(it.start, it.end) })
    }

    @Test
    fun `deleting the marked word removes the mark, trimming it shortens`() {
        val (state, marks) = editor("a = nope + typo\n")
        marks.publish(
            state.document.version,
            listOf(diagnostic(0, 4, 8), diagnostic(0, 11, 15)),
            PositionEncoding.Utf16,
        )

        state.replaceAll(EditTransaction.delete(4, 8))
        assertEquals(listOf(7 to 11), marks.spans(), "стёртое слово должно уйти, соседнее — сдвинуться")

        state.replaceAll(EditTransaction.delete(9, 11))
        assertEquals(listOf(7 to 9), marks.spans())
    }

    @Test
    fun `replacing the marked word removes the mark`() {
        // Про новый текст сервер ещё не сказал — старая ошибка к нему не относится.
        val (state, marks) = editor("x = nope\n")
        marks.publish(state.document.version, listOf(diagnostic(0, 4, 8)), PositionEncoding.Utf16)

        state.replaceAll(EditTransaction.replace(4, 8, "yes"))

        assertTrue(marks.marks.isEmpty())
    }

    @Test
    fun `undoing a typed word brings the mark back to its place`() {
        // Откат набора — три удаления за один шаг. Если пометка увидит только одно,
        // она встанет на две буквы правее, чем надо.
        val (state, marks) = editor("x = nope\n")
        for (letter in "abc") state.type(letter.toString())
        assertEquals("abcx = nope\n", state.text.toString())
        marks.publish(state.document.version, listOf(diagnostic(0, 7, 11)), PositionEncoding.Utf16)

        assertTrue(state.undo())

        assertEquals("x = nope\n", state.text.toString())
        assertEquals("nope", marks.marks.single().let { state.text.substring(it.start, it.end) })
    }

    @Test
    fun `moving the caret changes nothing`() {
        val (state, marks) = editor("x = nope\n")
        marks.publish(state.document.version, listOf(diagnostic(0, 4, 8)), PositionEncoding.Utf16)
        val revision = marks.revision
        var heard = 0
        marks.addListener { heard++ }

        state.setCarets(CaretSet.single(2))

        assertEquals(revision, marks.revision)
        assertEquals(0, heard, "движение курсора не должно будить перерисовку пометок")
        assertEquals(listOf(4 to 8), marks.spans())
    }

    @Test
    fun `an edit the marks did not see drops them rather than guessing`() {
        val (state, marks) = editor("x = nope\n")
        marks.publish(state.document.version, listOf(diagnostic(0, 4, 8)), PositionEncoding.Utf16)

        // Две правки мимо EditorState — пометки видят только последнюю.
        state.document.apply(EditTransaction.insert(0, "a"))
        state.document.apply(EditTransaction.insert(0, "b"))
        state.setCarets(CaretSet.single(3))

        assertTrue(marks.marks.isEmpty())
    }

    @Test
    fun `a single edit made past the editor is still followed`() {
        // Одна правка мимо EditorState видна целиком через lastChange — её
        // можно перенести честно, без сброса.
        val (state, marks) = editor("x = nope\n")
        marks.publish(state.document.version, listOf(diagnostic(0, 4, 8)), PositionEncoding.Utf16)

        state.document.apply(EditTransaction.insert(0, "ab"), EditKind.Other)
        state.setCarets(CaretSet.single(3))

        assertEquals(listOf(6 to 10), marks.spans())
    }

    @Test
    fun `an answer after a silent edit is followed from its own version`() {
        // Правка мимо редактора без уведомления, потом ответ для новой версии.
        // Следующая правка должна переносить пометки от версии ответа.
        val (state, marks) = editor("x = nope\n")
        state.document.apply(EditTransaction.insert(0, "#"))
        marks.publish(state.document.version, listOf(diagnostic(0, 5, 9)), PositionEncoding.Utf16)

        state.typeAt(0, "ab")

        assertEquals("nope", marks.marks.single().let { state.text.substring(it.start, it.end) })
    }

    @Test
    fun `the most serious mark under the caret wins, end included`() {
        val (state, marks) = editor("x = nope\n")
        marks.publish(
            state.document.version,
            listOf(
                diagnostic(0, 0, 8, Severity.Warning, "длинная строка"),
                diagnostic(0, 4, 8, Severity.Error, "неизвестное имя"),
            ),
            PositionEncoding.Utf16,
        )

        assertEquals("неизвестное имя", marks.at(8)?.message, "курсор сразу за словом — ещё на нём")
        assertEquals("длинная строка", marks.at(1)?.message)
        assertNull(marks.at(9))
        assertEquals(1, marks.count(Severity.Error))
        assertEquals(1, marks.count(Severity.Warning))
    }

    @Test
    fun `an empty range widens to one character, backwards at the end of a line`() {
        val (state, marks) = editor("def f(:\n\n😀x\n")

        marks.publish(
            state.document.version,
            listOf(
                diagnostic(0, 6, 6),
                diagnostic(0, 7, 7),
                diagnostic(1, 0, 0),
                diagnostic(2, 0, 0),
            ),
            PositionEncoding.Utf16,
        )

        val texts = marks.marks.map { state.text.substring(it.start, it.end) }
        assertEquals(listOf(":", ":", "", "😀"), texts, "эмодзи не режется пополам, пустая строка остаётся пустой")
    }

    @Test
    fun `a mark empty from the start survives unrelated edits`() {
        val (state, marks) = editor("x = 1\n\ny = 2\n")
        marks.publish(state.document.version, listOf(diagnostic(1, 0, 0)), PositionEncoding.Utf16)

        state.typeAt(0, "#")

        assertEquals(listOf(7 to 7), marks.spans())
    }

    @Test
    fun `a range past the end of the file is clamped, not thrown`() {
        val (state, marks) = editor("x = 1")

        marks.publish(state.document.version, listOf(diagnostic(0, 4, 99, toLine = 99)), PositionEncoding.Utf16)

        assertEquals(listOf(4 to 5), marks.spans())
    }

    @Test
    fun `closed marks stop following the editor`() {
        val (state, marks) = editor("x = nope\n")
        marks.publish(state.document.version, listOf(diagnostic(0, 4, 8)), PositionEncoding.Utf16)
        marks.close()

        state.typeAt(0, "abc")

        assertEquals(listOf(4 to 8), marks.spans())
    }

    @Test
    fun `several carets move marks by every insertion`() {
        val (state, marks) = editor("a\nb nope\n")
        marks.publish(state.document.version, listOf(diagnostic(1, 2, 6)), PositionEncoding.Utf16)

        state.setCarets(CaretSet.of(listOf(Caret(0), Caret(2))))
        state.type("xx")

        assertEquals("nope", marks.marks.single().let { state.text.substring(it.start, it.end) })
    }
}
