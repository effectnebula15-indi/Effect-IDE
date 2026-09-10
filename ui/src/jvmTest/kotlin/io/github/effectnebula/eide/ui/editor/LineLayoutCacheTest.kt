package io.github.effectnebula.eide.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineLayoutCacheTest {

    @Test
    fun `repeated lookup does not recompute`() {
        var computed = 0
        val cache = LineLayoutCache<Int>(capacity = 8)

        repeat(5) { assertEquals(42, cache.get("строка") { computed++; 42 }) }

        assertEquals(1, computed, "разметку одной и той же строки посчитали больше раза")
        assertEquals(4, cache.hits)
        assertEquals(1, cache.misses)
    }

    @Test
    fun `size stays bounded`() {
        val capacity = 16
        val cache = LineLayoutCache<Int>(capacity)

        repeat(1_000) { i -> cache.get("строка $i") { i } }

        // Два поколения по capacity — верхняя граница; важно, что она есть.
        assertTrue(cache.size <= capacity * 2, "кэш вырос до ${cache.size} при пределе $capacity")
    }

    @Test
    fun `recent lines survive a generation swap`() {
        val capacity = 4
        val cache = LineLayoutCache<Int>(capacity)

        // Заполняем молодое поколение и вытесняем его в старое.
        repeat(capacity) { i -> cache.get("старая $i") { i } }
        cache.get("новая", { -1 })

        var recomputed = 0
        // «старая 0» уехала в старое поколение, но должна найтись там.
        cache.get("старая 0") { recomputed++; 0 }

        assertEquals(0, recomputed, "строка из старого поколения посчиталась заново")
    }

    @Test
    fun `a screenful survives scrolling without recompute`() {
        // Сценарий ради которого кэш существует: пятьдесят видимых строк,
        // прокрутка туда и обратно в пределах одного экрана.
        val visible = (0 until 50).map { "line $it" }
        val cache = LineLayoutCache<Int>(capacity = 256)
        var computed = 0

        repeat(20) {
            for (line in visible) cache.get(line) { computed++; 1 }
            for (line in visible.reversed()) cache.get(line) { computed++; 1 }
        }

        assertEquals(visible.size, computed, "разметка пересчитывалась при прокрутке на месте")
    }

    @Test
    fun `clear resets content and counters`() {
        val cache = LineLayoutCache<Int>()
        cache.get("a") { 1 }
        cache.get("a") { 1 }

        cache.clear()

        assertEquals(0, cache.size)
        assertEquals(0, cache.hits)
        assertEquals(0, cache.misses)
    }
}
