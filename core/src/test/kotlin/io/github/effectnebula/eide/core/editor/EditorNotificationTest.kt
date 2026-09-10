package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Оповещение подписчиков — не мелочь: на нём держится и перерисовка экрана,
 * и синхронизация с системной клавиатурой.
 *
 * Пропущенное уведомление выглядит как «текст набирается, но появляется
 * с задержкой» — ошибка, которую на глаз списывают на тормоза телефона.
 */
class EditorNotificationTest {

    private object Breaker : GraphemeBreaker {
        override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
        override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
    }

    private class Counter : EditorListener {
        var calls = 0
            private set

        override fun onEditorChanged() {
            calls++
        }
    }

    private fun editor(text: String = "one\ntwo\nthree"): Pair<EditorState, Counter> {
        val state = EditorState(Document(Rope.of(text)), Breaker)
        val counter = Counter()
        state.addListener(counter)
        return state to counter
    }

    @Test
    fun `typing notifies listeners`() {
        val (state, counter) = editor()

        state.type("x")

        assertEquals(1, counter.calls)
    }

    @Test
    fun `every kind of change notifies listeners`() {
        val (state, counter) = editor()

        state.type("x")
        state.insertNewline()
        state.deleteBackward()
        state.deleteForward()
        state.move(MoveTo.Right)
        state.setCarets(CaretSet.single(0))
        state.selectAll()
        state.undo()
        state.redo()

        assertEquals(9, counter.calls, "какое-то из изменений прошло молча")
    }

    @Test
    fun `revision grows with every change`() {
        val (state, _) = editor()
        val before = state.revision

        state.type("x")
        state.move(MoveTo.Right)

        assertTrue(state.revision > before + 1, "ревизия отстала от изменений")
    }

    @Test
    fun `empty edit does not notify`() {
        val (state, counter) = editor()

        state.type("")

        assertEquals(0, counter.calls, "пустая правка разбудила подписчиков")
    }

    @Test
    fun `undo with empty history does not notify`() {
        val (state, counter) = editor()

        assertEquals(false, state.undo())

        assertEquals(0, counter.calls, "откат в пустой истории разбудил подписчиков")
    }

    @Test
    fun `caret cloned onto the same line does not notify`() {
        // Курсор на последней строке клонировать некуда — и это не изменение.
        val (state, counter) = editor("only line")

        state.addCaretBelow()

        assertEquals(0, counter.calls)
    }

    @Test
    fun `removed listener stops receiving`() {
        val (state, counter) = editor()

        state.type("x")
        state.removeListener(counter)
        state.type("y")

        assertEquals(1, counter.calls, "отписка не сработала")
    }

    @Test
    fun `several listeners all receive`() {
        val (state, first) = editor()
        val second = Counter()
        state.addListener(second)

        state.type("x")

        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }
}
