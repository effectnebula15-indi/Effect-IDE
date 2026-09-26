package io.github.effectnebula.eide.core.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class RopeCharSequenceTest {

    @Test
    fun `matches the string on sequential reads`() {
        val text = buildString { repeat(20_000) { append(('a' + it % 26)) } }
        val view = Rope.of(text).asCharSequence(windowSize = 64)

        assertEquals(text.length, view.length)
        for (i in text.indices) assertEquals(text[i], view[i], "символ $i")
    }

    @Test
    fun `matches the string on random access`() {
        // Движок регулярных выражений ходит по строке не только вперёд:
        // окно обязано переживать прыжки назад.
        val text = buildString { repeat(10_000) { append(('a' + it % 26)) } }
        val view = Rope.of(text).asCharSequence(windowSize = 128)
        val random = Random(5)

        repeat(20_000) {
            val i = random.nextInt(text.length)
            assertEquals(text[i], view[i], "символ $i")
        }
    }

    @Test
    fun `subsequence matches the string`() {
        val text = buildString { repeat(5_000) { append(('a' + it % 26)) } }
        val view = Rope.of(text).asCharSequence(windowSize = 100)
        val random = Random(9)

        repeat(200) {
            val from = random.nextInt(text.length)
            val to = from + random.nextInt(text.length - from + 1)
            assertEquals(text.substring(from, to), view.subSequence(from, to).toString())
        }
    }
}
