package io.github.effectnebula.eide.platform.desktop

import io.github.effectnebula.eide.platform.GraphemeBreaker
import java.text.BreakIterator

/**
 * Реализация на штатном [BreakIterator] из JDK.
 *
 * Честное ограничение: JDK-версия отстаёт от ICU на составных эмодзи (ZWJ-последовательности,
 * флаги, модификаторы цвета кожи) и разойдётся с Android, где ICU настоящий. Пока это заметно
 * только на экзотике; когда станет мешать — сюда приедет ICU4J, интерфейс не изменится.
 */
class JdkGraphemeBreaker : GraphemeBreaker {

    override fun next(line: CharSequence, from: Int): Int {
        require(from in 0..line.length) { "офсет $from вне [0, ${line.length}]" }
        if (from >= line.length) return line.length
        val iterator = iteratorFor(line)
        val next = iterator.following(from)
        return if (next == BreakIterator.DONE) line.length else next
    }

    override fun previous(line: CharSequence, from: Int): Int {
        require(from in 0..line.length) { "офсет $from вне [0, ${line.length}]" }
        if (from <= 0) return 0
        val iterator = iteratorFor(line)
        val previous = iterator.preceding(from)
        return if (previous == BreakIterator.DONE) 0 else previous
    }

    private fun iteratorFor(line: CharSequence): BreakIterator =
        BreakIterator.getCharacterInstance().apply { setText(line.toString()) }
}
