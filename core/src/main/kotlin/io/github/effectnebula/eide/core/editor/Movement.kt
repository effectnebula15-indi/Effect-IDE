package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker

/**
 * Перемещение курсора по документу.
 *
 * Все операции работают в офсетах документа, но границы символов спрашивают у
 * [GraphemeBreaker] на уровне строки: строка и так нужна для отрисовки, а гонять
 * по дереву весь текст ради одного шага курсора незачем.
 *
 * Колонка здесь — офсет от начала строки в UTF-16 единицах, а не визуальная позиция.
 * Для моноширинного шрифта без табуляций это одно и то же; когда появятся табуляции,
 * колонку придётся считать иначе, и вертикальное движение это заметит.
 */
class Movement(
    private val text: Rope,
    private val graphemes: GraphemeBreaker,
) {

    fun left(caret: Caret, keepSelection: Boolean): Caret {
        // Схлопывание выделения влево — это не шаг влево: курсор просто встаёт
        // на левый край выделенного.
        if (!keepSelection && !caret.isEmpty) return caret.movedTo(caret.start, false)

        val head = caret.head
        if (head <= 0) return caret.movedTo(0, keepSelection)

        val line = text.lineOf(head)
        val lineStart = text.lineStart(line)
        if (head == lineStart) {
            // Через перенос строки — в конец предыдущей.
            return caret.movedTo(text.lineEnd(line - 1), keepSelection)
        }
        val lineText = lineText(line)
        val within = graphemes.previous(lineText, head - lineStart)
        return caret.movedTo(lineStart + within, keepSelection)
    }

    fun right(caret: Caret, keepSelection: Boolean): Caret {
        if (!keepSelection && !caret.isEmpty) return caret.movedTo(caret.end, false)

        val head = caret.head
        if (head >= text.length) return caret.movedTo(text.length, keepSelection)

        val line = text.lineOf(head)
        val lineStart = text.lineStart(line)
        val lineEnd = text.lineEnd(line)
        if (head >= lineEnd) {
            // Стоим на конце содержимого строки: следующий шаг — начало следующей.
            return caret.movedTo(text.lineStart(line + 1), keepSelection)
        }
        val within = graphemes.next(lineText(line), head - lineStart)
        return caret.movedTo(lineStart + within, keepSelection)
    }

    fun wordLeft(caret: Caret, keepSelection: Boolean): Caret {
        var offset = caret.head
        if (offset <= 0) return caret.movedTo(0, keepSelection)

        offset--
        while (offset > 0 && !isWordChar(text.charAt(offset))) offset--
        while (offset > 0 && isWordChar(text.charAt(offset - 1))) offset--
        return caret.movedTo(offset, keepSelection)
    }

    fun wordRight(caret: Caret, keepSelection: Boolean): Caret {
        var offset = caret.head
        val length = text.length
        if (offset >= length) return caret.movedTo(length, keepSelection)

        while (offset < length && !isWordChar(text.charAt(offset))) offset++
        while (offset < length && isWordChar(text.charAt(offset))) offset++
        return caret.movedTo(offset, keepSelection)
    }

    /**
     * Home «с умом»: сначала к первому непробельному символу, и только при
     * повторном нажатии — в самое начало строки.
     */
    fun lineStart(caret: Caret, keepSelection: Boolean): Caret {
        val line = text.lineOf(caret.head)
        val start = text.lineStart(line)
        val end = text.lineEnd(line)

        var firstVisible = start
        while (firstVisible < end && text.charAt(firstVisible).isWhitespace()) firstVisible++

        val target = if (caret.head == firstVisible) start else firstVisible
        return caret.movedTo(target, keepSelection)
    }

    fun lineEnd(caret: Caret, keepSelection: Boolean): Caret {
        val line = text.lineOf(caret.head)
        return caret.movedTo(text.lineEnd(line), keepSelection)
    }

    fun up(caret: Caret, keepSelection: Boolean): Caret = vertical(caret, keepSelection, -1)

    fun down(caret: Caret, keepSelection: Boolean): Caret = vertical(caret, keepSelection, +1)

    private fun vertical(caret: Caret, keepSelection: Boolean, delta: Int): Caret {
        val line = text.lineOf(caret.head)
        val target = line + delta
        if (target < 0 || target >= text.lineCount) {
            // Упёрлись в край: колонку всё равно запоминаем, чтобы обратный ход вернул.
            val edge = if (delta < 0) 0 else text.length
            return caret.movedTo(edge, keepSelection, caret.desiredColumn ?: column(caret.head, line))
        }

        val desired = caret.desiredColumn ?: column(caret.head, line)
        val targetStart = text.lineStart(target)
        val targetEnd = text.lineEnd(target)
        val offset = snapToGrapheme(target, minOf(targetStart + desired, targetEnd))

        return caret.movedTo(offset, keepSelection, desired)
    }

    private fun column(offset: Int, line: Int): Int = offset - text.lineStart(line)

    /** Не даёт курсору встать посреди суррогатной пары или составной графемы. */
    private fun snapToGrapheme(line: Int, offset: Int): Int {
        val start = text.lineStart(line)
        if (offset <= start) return start
        val lineText = lineText(line)
        val within = offset - start
        var boundary = 0
        while (boundary < within) {
            val next = graphemes.next(lineText, boundary)
            if (next <= boundary) break
            if (next > within) return start + boundary
            boundary = next
        }
        return start + boundary
    }

    private fun lineText(line: Int): String =
        text.substring(text.lineStart(line), text.lineEnd(line))

    private companion object {
        /**
         * Что считать словом. Классификация своя, а не из ICU: программисту нужно,
         * чтобы `some_name` был одним словом, а `some name` — двумя, и это не
         * совпадает с правилами разбиения естественного языка.
         */
        fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
    }
}
