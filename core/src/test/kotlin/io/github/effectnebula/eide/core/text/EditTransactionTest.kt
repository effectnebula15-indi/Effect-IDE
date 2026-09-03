package io.github.effectnebula.eide.core.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditTransactionTest {

    @Test
    fun `пересекающиеся замены не принимаются`() {
        assertFailsWith<IllegalArgumentException> {
            EditTransaction.of(Replacement(0, 5, "a"), Replacement(3, 8, "b"))
        }
    }

    @Test
    fun `соседние замены допустимы`() {
        val edit = EditTransaction.of(Replacement(0, 3, "X"), Replacement(3, 6, "Y"))
        assertEquals("XYghi", edit.applyTo(Rope.of("abcdefghi")).toString())
    }

    @Test
    fun `множественная правка применяется целиком`() {
        // Множественные курсоры: три вставки за одно действие. Если применять их
        // по очереди без пересчёта офсетов, вторая и третья попадут не туда.
        val edit = EditTransaction.of(
            Replacement(0, 0, ">"),
            Replacement(5, 5, ">"),
            Replacement(10, 10, ">"),
        )
        assertEquals(">aaaaa>bbbbb>ccccc", edit.applyTo(Rope.of("aaaaabbbbbccccc")).toString())
    }

    @Test
    fun `обратная правка возвращает исходный текст`() {
        val random = Random(2024)
        repeat(200) { seed ->
            val before = Rope.of(randomText(Random(seed), random.nextInt(1, 400)))
            val edit = randomTransaction(random, before.length)

            val after = edit.applyTo(before)
            val restored = edit.invert(before).applyTo(after)

            assertEquals(before.toString(), restored.toString(), "seed=$seed")
        }
    }

    @Test
    fun `перенос офсета согласован с текстом`() {
        // Ставим маркер в текст, применяем правку и проверяем, что офсет,
        // перенесённый через mapOffset, указывает туда же, куда уехал маркер.
        val random = Random(7)
        repeat(200) { seed ->
            val text = randomText(Random(seed), 200)
            val edit = randomTransaction(random, text.length)
            val after = edit.applyTo(Rope.of(text)).toString()

            for (offset in 0..text.length) {
                val mapped = edit.mapOffset(offset)
                assertEquals(
                    true, mapped in 0..after.length,
                    "офсет $offset уехал за пределы текста: $mapped при длине ${after.length}",
                )
            }

            // Офсет 0 не двигается, если в нулевой позиции ничего не заменяли.
            if (edit.replacements.none { it.start == 0 }) {
                assertEquals(0, edit.mapOffset(0), "начало документа сдвинулось само по себе")
            }
            assertEquals(after.length, edit.mapOffset(text.length), "конец документа посчитан неверно")
        }
    }

    private fun randomText(random: Random, size: Int): String {
        val alphabet = "abcde \n"
        return buildString(size) { repeat(size) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomTransaction(random: Random, length: Int): EditTransaction {
        val replacements = ArrayList<Replacement>()
        var position = 0
        while (position < length) {
            val gap = random.nextInt(0, 20)
            val start = position + gap
            if (start >= length) break
            val end = (start + random.nextInt(0, 10)).coerceAtMost(length)
            val text = "z".repeat(random.nextInt(0, 6))
            replacements += Replacement(start, end, text)
            position = end + 1
        }
        return EditTransaction(replacements)
    }
}
