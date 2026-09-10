package io.github.effectnebula.eide.ui.editor

import io.github.effectnebula.eide.core.editor.Caret
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private object Breaker : GraphemeBreaker {
    override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
    override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
}

/**
 * Сам ряд — это отрисовка, её проверяет только глаз. А вот то, что каждая
 * кнопка что-то делает и делает ровно то, что на ней написано, проверяется
 * здесь: мёртвая кнопка в этом ряду выглядит как сломанная клавиатура.
 */
class ExtraKeyRowTest {

    private fun editor(text: String = "", at: Int = 0): EditorState {
        val state = EditorState(Document(Rope.of(text)), Breaker)
        state.setCarets(CaretSet.of(listOf(Caret(at))))
        return state
    }

    @Test
    fun `every key changes something`() {
        for (key in DEFAULT_EXTRA_KEYS) {
            // Документ с запасом с обеих сторон: иначе стрелка у края никуда не
            // сдвинется, и тест соврёт про мёртвую кнопку.
            val state = editor("one\ntwo\nthree", at = 5)

            // Предыстория, чтобы работали и откат, и повтор. Без неё тест
            // объявляет мёртвыми кнопки, которым просто нечего делать.
            state.type("x")
            state.document.breakGrouping()
            state.type("y")
            state.undo()
            state.setCarets(CaretSet.of(listOf(Caret(5))))

            val before = state.revision

            key.action(state)

            assertNotEquals(before, state.revision, "кнопка «${key.label}» ничего не делает")
        }
    }

    @Test
    fun `labels are unique`() {
        val duplicates = DEFAULT_EXTRA_KEYS.groupBy { it.label }.filterValues { it.size > 1 }

        assertTrue(duplicates.isEmpty(), "две кнопки с одной подписью: ${duplicates.keys}")
    }

    @Test
    fun `a punctuation key inserts exactly what is written on it`() {
        val punctuation = DEFAULT_EXTRA_KEYS.filter { it.label.length == 1 && it.label[0].code < 128 }

        assertTrue(punctuation.size > 20, "набор знаков подозрительно мал: ${punctuation.size}")
        for (key in punctuation) {
            val state = editor()

            key.action(state)

            assertEquals(key.label, state.text.substring(0, state.text.length), "кнопка «${key.label}»")
        }
    }

    @Test
    fun `the indent key inserts the same indent as Tab`() {
        val state = editor()

        DEFAULT_EXTRA_KEYS.first { it.label == "⇥" }.action(state)

        assertEquals(INDENT, state.text.substring(0, state.text.length))
    }
}
