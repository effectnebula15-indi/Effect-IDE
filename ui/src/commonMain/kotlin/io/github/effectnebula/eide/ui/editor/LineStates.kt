package io.github.effectnebula.eide.ui.editor

import io.github.effectnebula.eide.core.syntax.LineHighlight
import io.github.effectnebula.eide.core.syntax.LineHighlighter
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope

/**
 * Состояния лексера перед каждой строкой.
 *
 * Подсветка построчная, но кое-что пересекает границу строк — многострочные
 * строки в Python, блочные комментарии в C. Чтобы подсветить строку с середины
 * файла, надо знать, чем кончилась предыдущая, а для этого — все предыдущие.
 *
 * Отсюда кэш. Считается лениво, до нужной строки, и переживает прокрутку.
 *
 * **Цена, которую видно на больших файлах.** Первая прокрутка в конец файла на
 * сто тысяч строк разберёт их все — один раз, в главном потоке. Это десятки
 * миллисекунд, то есть заметный рывок. Лечится переносом разбора в фоновый
 * поток; пока не сделано, потому что фоновый разбор с кэшем — это уже половина
 * той работы, ради которой в плане стоит tree-sitter (ADR-007).
 */
internal class LineStates(private val highlighter: LineHighlighter) {

    private var states = IntArray(INITIAL_CAPACITY)
    /** До какой строки состояния посчитаны (не включая её саму). */
    private var computedUpTo = 0
    private var seenVersion = -1L

    /**
     * Состояние перед строкой [line], досчитывая при необходимости.
     *
     * [document] нужен, чтобы заметить правку: после неё состояния ниже места
     * правки могли измениться.
     */
    fun stateBefore(document: Document, line: Int): Int {
        syncWith(document)

        val text = document.text
        if (line <= 0) return LineHighlight.STATE_INITIAL

        // line + 2, а не line + 1: цикл ниже записывает состояние ПОСЛЕ строки
        // line, то есть states[line + 1]. С запасом на единицу меньше падение
        // случается ровно на строке 255, 511, 1023 — и только на ней.
        ensureCapacity(line + 2)
        while (computedUpTo <= line) {
            val previous = if (computedUpTo == 0) {
                LineHighlight.STATE_INITIAL
            } else {
                states[computedUpTo]
            }
            val index = computedUpTo
            if (index >= text.lineCount) {
                states[index + 1] = previous
            } else {
                states[index + 1] = highlighter
                    .highlight(lineText(text, index), previous)
                    .stateAfter
            }
            computedUpTo = index + 1
        }

        return states[line]
    }

    private fun syncWith(document: Document) {
        if (document.version == seenVersion) return

        val change = document.lastChange
        val changedLine = change?.edit?.replacements?.firstOrNull()?.start?.let { offset ->
            document.text.lineOf(offset.coerceIn(0, document.text.length))
        }

        // Версия прыгнула больше чем на единицу — правок между кадрами было
        // несколько, и место самой ранней нам уже не узнать. Пересчитываем всё:
        // редко и честно лучше, чем часто и с ошибкой.
        val jumped = seenVersion >= 0 && document.version - seenVersion > 1
        val from = if (jumped || changedLine == null) 0 else changedLine

        computedUpTo = minOf(computedUpTo, from)
        seenVersion = document.version
    }

    private fun ensureCapacity(size: Int) {
        if (states.size >= size) return
        var capacity = states.size
        while (capacity < size) capacity *= 2
        states = states.copyOf(capacity)
    }

    private fun lineText(text: Rope, line: Int): String =
        text.substring(text.lineStart(line), text.lineEnd(line))

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}
