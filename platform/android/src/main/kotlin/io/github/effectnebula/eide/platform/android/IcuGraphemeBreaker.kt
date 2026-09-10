package io.github.effectnebula.eide.platform.android

import android.icu.text.BreakIterator
import io.github.effectnebula.eide.platform.GraphemeBreaker

/**
 * Границы графем через ICU, встроенный в Android.
 *
 * Это тот же ICU, которым система разбивает текст в своих полях ввода, так что
 * курсор в нашем редакторе двигается так же, как везде на телефоне. Настольная
 * реализация на `java.text.BreakIterator` слабее и на составных эмодзи с ним
 * разойдётся — расхождение известное и записано в ADR-005.
 */
class IcuGraphemeBreaker : GraphemeBreaker {

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
