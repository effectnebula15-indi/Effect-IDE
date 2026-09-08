package io.github.effectnebula.eide.core.text

/**
 * Взгляд на [Rope] как на [CharSequence] — для регулярных выражений и всего, что
 * умеет работать с последовательностью символов, но ничего не знает про дерево.
 *
 * Наивная реализация с `rope.charAt(i)` на каждый символ дала бы O(log n) на
 * обращение, а движок регулярных выражений обращается к символам очень часто.
 * Поэтому здесь окно: подряд идущие обращения обслуживает один уже вытащенный
 * кусок текста, и спуск по дереву случается раз в [windowSize] символов.
 *
 * **Не потокобезопасен**: окно — изменяемое состояние. Сам rope неизменяем, так что
 * на один текст можно завести сколько угодно таких взглядов, по одному на поток.
 */
class RopeCharSequence(
    private val rope: Rope,
    private val windowSize: Int = 4096,
) : CharSequence {

    private var windowStart = 0
    private var windowEnd = 0
    private var window: String = ""

    override val length: Int get() = rope.length

    override fun get(index: Int): Char {
        if (index < windowStart || index >= windowEnd) fillWindow(index)
        return window[index - windowStart]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        rope.substring(startIndex, endIndex)

    override fun toString(): String = rope.toString()

    private fun fillWindow(index: Int) {
        require(index in 0 until length) { "индекс $index вне [0, $length)" }
        // Окно начинается на границе кратной windowSize: при движении в любую
        // сторону это даёт предсказуемое число перезагрузок.
        windowStart = (index / windowSize) * windowSize
        windowEnd = minOf(windowStart + windowSize, length)
        window = rope.substring(windowStart, windowEnd)
    }
}

/** Взгляд на текст как на последовательность символов. Не потокобезопасен. */
fun Rope.asCharSequence(windowSize: Int = 4096): CharSequence = RopeCharSequence(this, windowSize)
