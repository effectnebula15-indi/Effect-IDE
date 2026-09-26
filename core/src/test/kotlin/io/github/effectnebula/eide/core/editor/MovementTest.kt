package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Границы графем в тестах считаются по суррогатным парам.
 *
 * Настоящая реализация берёт ICU и умеет больше (составные эмодзи, флаги), но
 * для проверки самой навигации важно ровно одно: курсор не должен вставать
 * посреди пары.
 */
private object SurrogateAwareBreaker : GraphemeBreaker {
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

class MovementTest {

    private fun movement(text: String) = Movement(Rope.of(text), SurrogateAwareBreaker)

    @Test
    fun `right step crosses the line break`() {
        val m = movement("ab\ncd")
        // Офсет 2 — конец первой строки, перед '\n'.
        assertEquals(3, m.right(Caret(2), keepSelection = false).head)
    }

    @Test
    fun `left step lands at end of previous line`() {
        val m = movement("ab\ncd")
        assertEquals(2, m.left(Caret(3), keepSelection = false).head)
    }

    @Test
    fun `step never lands inside a surrogate pair`() {
        val m = movement("a😀b")
        val afterEmoji = m.right(m.right(Caret(0), false), false)
        assertEquals(3, afterEmoji.head, "курсор встал посреди пары")
        assertEquals(1, m.left(afterEmoji, false).head)
    }

    @Test
    fun `right step collapses selection to its right edge`() {
        val m = movement("abcdef")
        val selected = Caret(anchor = 1, head = 4)
        assertEquals(4, m.right(selected, keepSelection = false).head)
        assertEquals(true, m.right(selected, keepSelection = false).isEmpty)
    }

    @Test
    fun `word motion treats underscore as part of the word`() {
        val m = movement("some_name other")
        assertEquals(9, m.wordRight(Caret(0), false).head, "some_name должно быть одним словом")
        assertEquals(15, m.wordRight(Caret(9), false).head)
        assertEquals(10, m.wordLeft(Caret(15), false).head)
        assertEquals(0, m.wordLeft(Caret(9), false).head)
    }

    @Test
    fun `home goes to text first then to line start`() {
        val m = movement("    отступ")
        val atText = m.lineStart(Caret(10), false)
        assertEquals(4, atText.head, "первый Home должен встать перед текстом")

        val atStart = m.lineStart(atText, false)
        assertEquals(0, atStart.head, "второй Home должен встать в начало строки")
    }

    @Test
    fun `vertical motion remembers column across a short line`() {
        // Классическая ошибка: пройти вниз через короткую строку и вернуться
        // вверх — курсор должен оказаться там же, откуда ушёл.
        val m = movement("длинная строка\nкор\nдлинная строка")
        val start = Caret(10) // колонка 10 в первой строке

        val down = m.down(start, false)
        assertEquals(18, down.head, "на короткой строке курсор должен встать в её конец")

        val downAgain = m.down(down, false)
        assertEquals(29, downAgain.head, "колонка не восстановилась на длинной строке")

        val backUp = m.up(m.up(downAgain, false), false)
        assertEquals(10, backUp.head, "возврат вверх привёл не в исходную колонку")
    }

    @Test
    fun `up from first line goes to document start`() {
        val m = movement("abc\ndef")
        assertEquals(0, m.up(Caret(2), false).head)
    }

    @Test
    fun `down from last line goes to document end`() {
        val m = movement("abc\ndef")
        assertEquals(7, m.down(Caret(5), false).head)
    }

    @Test
    fun `end stops before the line break`() {
        val m = movement("abc\ndef")
        assertEquals(3, m.lineEnd(Caret(1), false).head)
    }

    @Test
    fun `anchor stays put while extending selection`() {
        val m = movement("abcdef")
        val moved = m.right(m.right(Caret(2), keepSelection = true), keepSelection = true)
        assertEquals(2, moved.anchor)
        assertEquals(4, moved.head)
    }
}
