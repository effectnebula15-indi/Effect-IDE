package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaretSetTest {

    @Test
    fun `carets are ordered`() {
        val set = CaretSet.of(listOf(Caret(10), Caret(2), Caret(7)))
        assertEquals(listOf(2, 7, 10), set.carets.map { it.head })
    }

    @Test
    fun `overlapping carets merge`() {
        // Иначе набор текста продублируется, а выделения станут неразличимы.
        val set = CaretSet.of(listOf(Caret(0, 5), Caret(3, 8)))
        assertEquals(1, set.carets.size)
        assertEquals(0, set.carets.single().start)
        assertEquals(8, set.carets.single().end)
    }

    @Test
    fun `touching carets merge too`() {
        val set = CaretSet.of(listOf(Caret(0, 3), Caret(3, 6)))
        assertEquals(1, set.carets.size)
    }

    @Test
    fun `empty caret set is refused`() {
        assertFailsWith<IllegalArgumentException> { CaretSet.of(emptyList()) }
    }

    @Test
    fun `typing at every caret yields one transaction`() {
        val set = CaretSet.of(listOf(Caret(0), Caret(5), Caret(10)))
        val edit = set.typing(">")

        assertEquals(">aaaaa>bbbbb>ccccc", edit.applyTo(Rope.of("aaaaabbbbbccccc")).toString())
    }

    @Test
    fun `typing over selections replaces them`() {
        val set = CaretSet.of(listOf(Caret(0, 3), Caret(6, 9)))
        val edit = set.typing("X")
        assertEquals("XdefX", edit.applyTo(Rope.of("abcdefghi")).toString())
    }

    @Test
    fun `carets are mapped through an edit`() {
        val set = CaretSet.of(listOf(Caret(0), Caret(5), Caret(10)))
        val edit = EditTransaction.insert(0, "###")

        val moved = set.afterEdit(edit)

        assertEquals(listOf(3, 8, 13), moved.carets.map { it.head })
    }

    @Test
    fun `caret ends up after the typed character`() {
        // Регрессия: раньше вставка ровно в позицию курсора его не двигала, и
        // каждая нажатая клавиша отталкивала курсор назад.
        var carets = CaretSet.of(listOf(Caret(3)))
        var text = Rope.of("abcdef")

        for (letter in "XYZ") {
            val edit = carets.typing(letter.toString())
            text = edit.applyTo(text)
            carets = carets.afterEdit(edit)
        }

        assertEquals("abcXYZdef", text.toString())
        assertEquals(6, carets.primary.head, "курсор отстал от набранного текста")
    }

    @Test
    fun `typing at several carets keeps their order`() {
        var carets = CaretSet.of(listOf(Caret(0), Caret(3), Caret(6)))
        var text = Rope.of("aaabbbccc")

        repeat(2) {
            val edit = carets.typing("-")
            text = edit.applyTo(text)
            carets = carets.afterEdit(edit)
        }

        assertEquals("--aaa--bbb--ccc", text.toString())
        assertEquals(listOf(2, 7, 12), carets.carets.map { it.head })
    }

    @Test
    fun `carets collapsed to one point by an edit merge`() {
        // Удаляем всё между двумя курсорами: они оказываются в одном месте,
        // и дальше должны вести себя как один.
        val set = CaretSet.of(listOf(Caret(2), Caret(8)))
        val moved = set.afterEdit(EditTransaction.delete(2, 8))

        assertEquals(1, moved.carets.size)
        assertEquals(2, moved.carets.single().head)
    }
}
