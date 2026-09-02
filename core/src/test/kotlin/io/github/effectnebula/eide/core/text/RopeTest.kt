package io.github.effectnebula.eide.core.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Тесты rope устроены как сравнение с эталонной моделью: тот же набор правок
 * применяется к [StringBuilder], и результаты должны совпадать посимвольно.
 *
 * Случайность детерминированная (фиксированные seed'ы): упавший тест воспроизводится
 * запуском того же seed'а, а не «иногда падает на CI».
 */
class RopeTest {

    @Test
    fun `пустой rope`() {
        assertEquals(0, Rope.EMPTY.length)
        assertEquals(1, Rope.EMPTY.lineCount)
        assertEquals("", Rope.EMPTY.toString())
        assertTrue(Rope.EMPTY.isEmpty())
    }

    @Test
    fun `отдаёт исходный текст любой длины`() {
        for (size in intArrayOf(0, 1, 100, Rope.MAX_LEAF - 1, Rope.MAX_LEAF, Rope.MAX_LEAF + 1, 100_000)) {
            val text = randomText(Random(size), size)
            assertEquals(text, Rope.of(text).toString(), "размер $size")
        }
    }

    @Test
    fun `случайные правки совпадают с эталонной моделью`() {
        repeat(20) { seed ->
            val random = Random(seed)
            var rope = Rope.of(randomText(random, 2_000))
            val model = StringBuilder(rope.toString())

            repeat(300) { step ->
                when (random.nextInt(3)) {
                    0 -> {
                        val at = random.nextInt(model.length + 1)
                        val text = randomText(random, random.nextInt(1, 40))
                        rope = rope.insert(at, text)
                        model.insert(at, text)
                    }
                    1 -> if (model.isNotEmpty()) {
                        val from = random.nextInt(model.length)
                        val to = from + random.nextInt(model.length - from + 1)
                        rope = rope.delete(from, to)
                        model.delete(from, to)
                    }
                    else -> if (model.isNotEmpty()) {
                        val from = random.nextInt(model.length)
                        val to = from + random.nextInt(model.length - from + 1)
                        val text = randomText(random, random.nextInt(0, 20))
                        rope = rope.replace(from, to, text)
                        model.replace(from, to, text)
                    }
                }

                assertEquals(model.length, rope.length, "seed=$seed шаг=$step длина")
                checkInvariants(rope, "seed=$seed шаг=$step")
            }

            assertEquals(model.toString(), rope.toString(), "seed=$seed итог")
        }
    }

    @Test
    fun `дерево остаётся сбалансированным при вставке в конец`() {
        // Худший случай для наивной склейки: дерево вырождается в список.
        var rope = Rope.EMPTY
        repeat(5_000) { rope = rope.insert(rope.length, "x") }

        assertEquals(5_000, rope.length)
        checkInvariants(rope, "вставка в конец")
        // При 5000 символов и листе в 1024 символа листьев единицы, высота обязана быть маленькой.
        assertTrue(rope.root.height < 20, "высота ${rope.root.height} — дерево вырождается")
    }

    @Test
    fun `substring и appendTo совпадают с моделью`() {
        val random = Random(1234)
        val text = randomText(random, 20_000)
        val rope = Rope.of(text)

        repeat(500) {
            val from = random.nextInt(text.length)
            val to = from + random.nextInt(text.length - from + 1)
            assertEquals(text.substring(from, to), rope.substring(from, to), "срез [$from, $to)")
        }
    }

    @Test
    fun `charAt совпадает с моделью`() {
        val text = randomText(Random(7), 5_000)
        val rope = Rope.of(text)
        for (i in text.indices) assertEquals(text[i], rope.charAt(i), "символ $i")
    }

    @Test
    fun `строки и офсеты совпадают с моделью`() {
        val random = Random(99)
        val text = randomText(random, 30_000)
        val rope = Rope.of(text)

        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1

        assertEquals(starts.size, rope.lineCount)
        for (line in starts.indices) {
            assertEquals(starts[line], rope.lineStart(line), "начало строки $line")
        }
        for (offset in 0..text.length) {
            val expected = text.take(offset).count { it == '\n' }
            assertEquals(expected, rope.lineOf(offset), "номер строки для офсета $offset")
        }
    }

    @Test
    fun `lineEnd отбрасывает перенос и предшествующий возврат каретки`() {
        val rope = Rope.of("aaa\r\nbb\nccc")
        assertEquals(3, rope.lineEnd(0)) // до '\r'
        assertEquals(7, rope.lineEnd(1)) // до '\n'
        assertEquals(11, rope.lineEnd(2)) // конец текста
        assertEquals("aaa", rope.substring(rope.lineStart(0), rope.lineEnd(0)))
        assertEquals("bb", rope.substring(rope.lineStart(1), rope.lineEnd(1)))
        assertEquals("ccc", rope.substring(rope.lineStart(2), rope.lineEnd(2)))
    }

    @Test
    fun `суррогатные пары не разрываются при нарезке на листья`() {
        // Одна эмодзи — две UTF-16 code unit. Нарезка на чанки не должна их разделять.
        val emoji = "😀" // 😀
        val text = emoji.repeat(5_000)
        val rope = Rope.of(text)

        assertEquals(text, rope.toString())
        val node = rope.root
        val leaves = ArrayList<String>()
        fun walk(n: Rope.Node) {
            when (n) {
                is Rope.Leaf -> leaves += n.text
                is Rope.Branch -> {
                    walk(n.left)
                    walk(n.right)
                }
            }
        }
        walk(node)
        for ((i, leaf) in leaves.withIndex()) {
            assertTrue(leaf.isEmpty() || !leaf.last().isHighSurrogate(), "лист $i кончается половиной пары")
            assertTrue(leaf.isEmpty() || !leaf.first().isLowSurrogate(), "лист $i начинается половиной пары")
        }
    }

    @Test
    fun `выход за границы это ошибка вызывающего`() {
        val rope = Rope.of("abc")
        assertFailsWith<IllegalArgumentException> { rope.charAt(3) }
        assertFailsWith<IllegalArgumentException> { rope.insert(4, "x") }
        assertFailsWith<IllegalArgumentException> { rope.delete(2, 1) }
        assertFailsWith<IllegalArgumentException> { rope.lineStart(1) }
    }

    // --- вспомогательное ---------------------------------------------------------

    private fun randomText(random: Random, size: Int): String {
        val alphabet = "abcdefgh \n\n\tпривет"
        return buildString(size) {
            repeat(size) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    /** Проверяет инварианты дерева: AVL-баланс и согласованность накопленных сумм. */
    private fun checkInvariants(rope: Rope, where: String) {
        fun check(n: Rope.Node): Triple<Int, Int, Int> = when (n) {
            is Rope.Leaf -> {
                assertTrue(n.text.length <= Rope.MAX_LEAF, "$where: лист длиннее MAX_LEAF")
                Triple(n.text.length, n.text.count { it == '\n' }, 0)
            }
            is Rope.Branch -> {
                val (ll, ln, lh) = check(n.left)
                val (rl, rn, rh) = check(n.right)
                assertTrue(kotlin.math.abs(lh - rh) <= 1, "$where: перекос ${lh - rh}")
                assertEquals(ll + rl, n.length, "$where: длина узла")
                assertEquals(ln + rn, n.newlines, "$where: переносы узла")
                Triple(ll + rl, ln + rn, 1 + maxOf(lh, rh))
            }
        }
        check(rope.root)
    }
}
