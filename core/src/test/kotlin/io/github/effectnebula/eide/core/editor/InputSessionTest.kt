package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Разговор с системной клавиатурой.
 *
 * Проверяется без Android намеренно: `InputConnection` можно потрогать только
 * на телефоне, а ошибки здесь тихие — текст «почти правильный», и замечают это
 * не сразу. Отсюда и правило: вся логика в `:core`, на Android — только адаптер.
 */
class InputSessionTest {

    private object Breaker : GraphemeBreaker {
        override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
        override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
    }

    private fun session(text: String, vararg caretsAt: Int): Pair<EditorState, InputSession> {
        val state = EditorState(Document(Rope.of(text)), Breaker)
        if (caretsAt.isNotEmpty()) state.setCarets(CaretSet.of(caretsAt.map { Caret(it) }))
        return state to InputSession(state)
    }

    private fun EditorState.dump(): String = text.substring(0, text.length)

    // --- фиксация текста -------------------------------------------------------

    @Test
    fun `commit inserts at the caret`() {
        val (state, ime) = session("ab", 1)

        ime.commitText("X", 1)

        assertEquals("aXb", state.dump())
        assertEquals(TextSpan(2, 2), ime.selection)
    }

    @Test
    fun `commit replaces the selection`() {
        val state = EditorState(Document(Rope.of("hello")), Breaker)
        state.setCarets(CaretSet.of(listOf(Caret(anchor = 1, head = 4))))
        val ime = InputSession(state)

        ime.commitText("i", 1)

        assertEquals("hio", state.dump())
    }

    @Test
    fun `commit with a non-positive position puts the caret before the text`() {
        // Правило Android: 0 — перед вставленным текстом, 1 — сразу после.
        val (_, ime) = session("ab", 1)

        ime.commitText("XY", 0)

        assertEquals(TextSpan(1, 1), ime.selection)
    }

    @Test
    fun `commit with a position beyond one moves the caret further`() {
        // Правило Android для положительных значений: отсчёт от конца
        // вставленного текста, где 1 — сразу за ним, 2 — на символ дальше.
        val (_, ime) = session("abcd", 0)

        ime.commitText("X", 2)

        assertEquals(TextSpan(2, 2), ime.selection)
    }

    @Test
    fun `commit with a negative position moves the caret back`() {
        val (_, ime) = session("abcd", 2)

        ime.commitText("XY", -1)

        assertEquals(TextSpan(1, 1), ime.selection)
    }

    @Test
    fun `commit closes the draft`() {
        val (state, ime) = session("ab", 1)

        ime.setComposingText("пр", 1)
        ime.commitText("привет", 1)

        assertEquals("aприветb", state.dump())
        assertNull(ime.composing, "черновик пережил фиксацию")
    }

    // --- черновик --------------------------------------------------------------

    @Test
    fun `draft is rewritten whole, not appended`() {
        // Свайп-ввод и автодополнение работают именно так: клавиатура каждый раз
        // присылает слово целиком. Если черновик не отслеживать, в тексте
        // остаётся «пппрприпривет».
        val (state, ime) = session("()", 1)

        ime.setComposingText("п", 1)
        ime.setComposingText("пр", 1)
        ime.setComposingText("привет", 1)

        assertEquals("(привет)", state.dump())
        assertEquals(TextSpan(1, 7), ime.composing)
    }

    @Test
    fun `empty draft removes it`() {
        val (state, ime) = session("ab", 1)

        ime.setComposingText("xyz", 1)
        ime.setComposingText("", 1)

        assertEquals("ab", state.dump())
        assertNull(ime.composing)
    }

    @Test
    fun `finishing the draft keeps the text`() {
        val (state, ime) = session("", 0)

        ime.setComposingText("code", 1)
        ime.finishComposing()

        assertEquals("code", state.dump())
        assertNull(ime.composing)
        // Следующая правка идёт уже в выделение, а не в бывший черновик.
        ime.commitText("!", 1)
        assertEquals("code!", state.dump())
    }

    @Test
    fun `composing region can be set on existing text`() {
        val (_, ime) = session("hello world", 5)

        ime.setComposingRegion(0, 5)

        assertEquals(TextSpan(0, 5), ime.composing)
    }

    @Test
    fun `empty composing region means no draft`() {
        val (_, ime) = session("hello", 5)

        ime.setComposingRegion(2, 2)

        assertNull(ime.composing)
    }

    @Test
    fun `stale offsets from the keyboard are clamped, not fatal`() {
        // Клавиатура — отдельный процесс, её представление о тексте отстаёт.
        val (_, ime) = session("ab", 2)

        ime.setComposingRegion(-5, 900)

        assertEquals(TextSpan(0, 2), ime.composing)
    }

    // --- удаление вокруг курсора -----------------------------------------------

    @Test
    fun `delete before the caret`() {
        val (state, ime) = session("abcdef", 3)

        ime.deleteSurrounding(2, 0)

        assertEquals("adef", state.dump())
        assertEquals(TextSpan(1, 1), ime.selection)
    }

    @Test
    fun `delete after the caret`() {
        val (state, ime) = session("abcdef", 3)

        ime.deleteSurrounding(0, 2)

        assertEquals("abcf", state.dump())
        assertEquals(TextSpan(3, 3), ime.selection)
    }

    @Test
    fun `delete on both sides keeps the selection`() {
        val state = EditorState(Document(Rope.of("aaBBBcc")), Breaker)
        state.setCarets(CaretSet.of(listOf(Caret(anchor = 2, head = 5))))
        val ime = InputSession(state)

        ime.deleteSurrounding(2, 2)

        assertEquals("BBB", state.dump())
        assertEquals(TextSpan(0, 3), ime.selection, "выделение потерялось при удалении вокруг него")
    }

    @Test
    fun `delete more than there is stops at the edges`() {
        val (state, ime) = session("ab", 1)

        ime.deleteSurrounding(100, 100)

        assertEquals("", state.dump())
    }

    @Test
    fun `delete does not cut a surrogate pair in half`() {
        // "a" + 😀 (две единицы UTF-16) + "b"; клавиатура просит удалить одну.
        val (state, ime) = session("a😀b", 3)

        ime.deleteSurrounding(1, 0)

        assertEquals("ab", state.dump(), "эмодзи разрезано пополам — документ больше не валидный UTF-16")
    }

    @Test
    fun `delete in code points removes a whole emoji`() {
        val (state, ime) = session("a😀b", 3)

        ime.deleteSurroundingInCodePoints(1, 0)

        assertEquals("ab", state.dump())
    }

    @Test
    fun `delete of nothing changes nothing`() {
        val (state, ime) = session("ab", 0)
        val revision = state.revision

        ime.deleteSurrounding(1, 0)

        assertEquals("ab", state.dump())
        assertEquals(revision, state.revision, "пустое удаление разбудило подписчиков")
    }

    // --- выделение -------------------------------------------------------------

    @Test
    fun `set selection moves the caret`() {
        val (_, ime) = session("hello", 0)

        ime.setSelection(1, 4)

        assertEquals(TextSpan(1, 4), ime.selection)
    }

    @Test
    fun `reading around the caret`() {
        val (_, ime) = session("abcdef", 3)

        assertEquals("bc", ime.textBeforeCursor(2))
        assertEquals("de", ime.textAfterCursor(2))
        assertEquals("abc", ime.textBeforeCursor(100), "чтение за краем документа должно обрезаться")
    }

    @Test
    fun `reading the selected text`() {
        val state = EditorState(Document(Rope.of("hello")), Breaker)
        state.setCarets(CaretSet.of(listOf(Caret(anchor = 1, head = 4))))

        assertEquals("ell", InputSession(state).selectedText())
    }

    // --- мультикурсор ----------------------------------------------------------

    @Test
    fun `plain commit reaches every caret`() {
        // Единственный способ печатать на телефоне без внешней клавиатуры —
        // экранная. Терять на ней мультикурсор было бы обидно.
        val (state, ime) = session("aaabbbccc", 0, 3, 6)

        ime.commitText("-", 1)

        assertEquals("-aaa-bbb-ccc", state.dump())
    }

    @Test
    fun `backspace reaches every caret`() {
        val (state, ime) = session("aaabbbccc", 3, 6, 9)

        ime.deleteSurrounding(1, 0)

        assertEquals("aabbcc", state.dump())
    }

    @Test
    fun `a draft gives up the extra carets`() {
        // Черновик адресуется абсолютными офсетами, и других курсоров IME не видит.
        val (state, ime) = session("aaabbb", 0, 3)

        ime.setComposingText("x", 1)

        assertEquals(1, state.carets.carets.size)
        assertEquals("aaaxbbb", state.dump())
    }

    // --- совместная жизнь с undo -----------------------------------------------

    @Test
    fun `typed word rolls back as one action`() {
        val (state, ime) = session("", 0)

        ime.commitText("h", 1)
        ime.commitText("i", 1)
        state.undo()

        assertEquals("", state.dump(), "набор из двух символов откатился по буквам")
    }
}
