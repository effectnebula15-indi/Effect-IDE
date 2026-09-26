package io.github.effectnebula.eide.core.syntax

/**
 * Лексер Python для подсветки.
 *
 * Знает лексику, а не язык (ADR-007). Отсюда всё, чего он не умеет: `match` он
 * покрасит как ключевое слово даже там, где это имя переменной, а имя функции
 * узнает только сразу после `def`. Это видно глазами и это цена решения, а не
 * недоделка.
 *
 * Состояние между строками — только «мы внутри многострочной строки и какой».
 * Без него докстринг подсвечивался бы как код, а это половина типичного файла.
 */
object PythonHighlighter : LineHighlighter {

    override fun highlight(line: String, stateBefore: Int): LineHighlight {
        val spans = ArrayList<HighlightSpan>()
        var index = 0
        var state = stateBefore

        // Продолжение многострочной строки с прошлой строки.
        if (state != LineHighlight.STATE_INITIAL) {
            val quote = quoteOf(state)
            val closing = line.indexOf(quote)
            if (closing < 0) {
                return LineHighlight(listOf(HighlightSpan(0, line.length, TokenKind.String)), state)
            }
            val end = closing + quote.length
            spans += HighlightSpan(0, end, TokenKind.String)
            index = end
            state = LineHighlight.STATE_INITIAL
        }

        while (index < line.length) {
            val char = line[index]

            when {
                char == '#' -> {
                    spans += HighlightSpan(index, line.length, TokenKind.Comment)
                    return LineHighlight(spans, state)
                }

                char == '"' || char == '\'' -> {
                    val consumed = readString(line, index, spans)
                    if (consumed.state != LineHighlight.STATE_INITIAL) {
                        return LineHighlight(spans, consumed.state)
                    }
                    index = consumed.next
                }

                char.isDigit() -> index = readNumber(line, index, spans)

                isWordStart(char) -> index = readWord(line, index, spans)

                else -> index++
            }
        }

        return LineHighlight(spans, state)
    }

    // --- строки ----------------------------------------------------------------

    private class StringScan(val next: Int, val state: Int)

    private fun readString(line: String, start: Int, spans: MutableList<HighlightSpan>): StringScan {
        val quote = line[start]
        val triple = line.startsWith("$quote$quote$quote", start)

        if (triple) {
            val marker = "$quote$quote$quote"
            val closing = line.indexOf(marker, start + 3)
            if (closing < 0) {
                spans += HighlightSpan(start, line.length, TokenKind.String)
                return StringScan(line.length, stateFor(quote))
            }
            val end = closing + 3
            spans += HighlightSpan(start, end, TokenKind.String)
            return StringScan(end, LineHighlight.STATE_INITIAL)
        }

        var i = start + 1
        while (i < line.length) {
            // Экранированная кавычка строку не закрывает; экранированный слеш
            // не экранирует следующий за ним символ.
            if (line[i] == '\\' && i + 1 < line.length) {
                i += 2
                continue
            }
            if (line[i] == quote) {
                spans += HighlightSpan(start, i + 1, TokenKind.String)
                return StringScan(i + 1, LineHighlight.STATE_INITIAL)
            }
            i++
        }

        // Незакрытая одинарная строка кончается вместе со строкой файла: перенос
        // внутри неё в Python запрещён, и тащить состояние дальше нельзя.
        spans += HighlightSpan(start, line.length, TokenKind.String)
        return StringScan(line.length, LineHighlight.STATE_INITIAL)
    }

    /** Состояние кодирует, какой кавычкой открыта многострочная строка. */
    private fun stateFor(quote: Char): Int = if (quote == '"') STATE_TRIPLE_DOUBLE else STATE_TRIPLE_SINGLE

    private fun quoteOf(state: Int): String = if (state == STATE_TRIPLE_DOUBLE) "\"\"\"" else "'''"

    // --- числа и слова ----------------------------------------------------------

    private fun readNumber(line: String, start: Int, spans: MutableList<HighlightSpan>): Int {
        var i = start
        // Подчёркивания в числах — часть синтаксиса Python: 1_000_000.
        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '.' || line[i] == '_')) i++
        spans += HighlightSpan(start, i, TokenKind.Number)
        return i
    }

    private fun readWord(line: String, start: Int, spans: MutableList<HighlightSpan>): Int {
        var i = start
        while (i < line.length && isWordPart(line[i])) i++
        val word = line.substring(start, i)

        when {
            word in KEYWORDS -> {
                spans += HighlightSpan(start, i, TokenKind.Keyword)
                // Имя сразу после def или class — то, что глаз ищет при
                // беглом просмотре файла.
                if (word == "def" || word == "class") {
                    readDeclarationName(line, i, spans)?.let { return it }
                }
            }
            word in CONSTANTS -> spans += HighlightSpan(start, i, TokenKind.Keyword)
        }
        return i
    }

    private fun readDeclarationName(line: String, after: Int, spans: MutableList<HighlightSpan>): Int? {
        var i = after
        while (i < line.length && line[i] == ' ') i++
        if (i >= line.length || !isWordStart(line[i])) return null

        val start = i
        while (i < line.length && isWordPart(line[i])) i++
        spans += HighlightSpan(start, i, TokenKind.Declaration)
        return i
    }

    /**
     * Буква по Unicode, а не по ASCII.
     *
     * Идентификаторы в Python могут быть на любом языке, и в этом проекте
     * такие файлы точно появятся — примеры в нём написаны по-русски.
     */
    private fun isWordStart(char: Char): Boolean = char.isLetter() || char == '_'

    private fun isWordPart(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    private const val STATE_TRIPLE_SINGLE = 1
    private const val STATE_TRIPLE_DOUBLE = 2

    private val KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
        "del", "elif", "else", "except", "finally", "for", "from", "global", "if",
        "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
        "return", "try", "while", "with", "yield",
    )

    /** Не ключевые слова грамматики, но красить их как код — привычно и полезно. */
    private val CONSTANTS = setOf("True", "False", "None", "self", "cls")
}
