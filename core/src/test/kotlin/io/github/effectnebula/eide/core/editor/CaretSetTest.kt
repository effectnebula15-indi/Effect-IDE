package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaretSetTest {

    @Test
    fun `курсоры упорядочиваются`() {
        val set = CaretSet.of(listOf(Caret(10), Caret(2), Caret(7)))
        assertEquals(listOf(2, 7, 10), set.carets.map { it.head })
    }

    @Test
    fun `наехавшие друг на друга курсоры сливаются`() {
        // Иначе набор текста продублируется, а выделения станут неразличимы.
        val set = CaretSet.of(listOf(Caret(0, 5), Caret(3, 8)))
        assertEquals(1, set.carets.size)
        assertEquals(0, set.carets.single().start)
        assertEquals(8, set.carets.single().end)
    }

    @Test
    fun `соприкасающиеся курсоры тоже сливаются`() {
        val set = CaretSet.of(listOf(Caret(0, 3), Caret(3, 6)))
        assertEquals(1, set.carets.size)
    }

    @Test
    fun `пустой набор невозможен`() {
        assertFailsWith<IllegalArgumentException> { CaretSet.of(emptyList()) }
    }

    @Test
    fun `набор текста во всех курсорах даёт одну транзакцию`() {
        val set = CaretSet.of(listOf(Caret(0), Caret(5), Caret(10)))
        val edit = set.typing(">")

        assertEquals(">aaaaa>bbbbb>ccccc", edit.applyTo(Rope.of("aaaaabbbbbccccc")).toString())
    }

    @Test
    fun `набор поверх выделений заменяет их`() {
        val set = CaretSet.of(listOf(Caret(0, 3), Caret(6, 9)))
        val edit = set.typing("X")
        assertEquals("XdefX", edit.applyTo(Rope.of("abcdefghi")).toString())
    }

    @Test
    fun `курсоры переносятся через правку`() {
        val set = CaretSet.of(listOf(Caret(0), Caret(5), Caret(10)))
        val edit = EditTransaction.insert(0, "###")

        val moved = set.afterEdit(edit)

        assertEquals(listOf(3, 8, 13), moved.carets.map { it.head })
    }

    @Test
    fun `после набора курсор стоит за набранным символом`() {
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
    fun `набор в нескольких курсорах не сбивает их порядок`() {
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
    fun `курсоры, схлопнувшиеся в одну точку после правки, сливаются`() {
        // Удаляем всё между двумя курсорами: они оказываются в одном месте,
        // и дальше должны вести себя как один.
        val set = CaretSet.of(listOf(Caret(2), Caret(8)))
        val moved = set.afterEdit(EditTransaction.delete(2, 8))

        assertEquals(1, moved.carets.size)
        assertEquals(2, moved.carets.single().head)
    }
}
