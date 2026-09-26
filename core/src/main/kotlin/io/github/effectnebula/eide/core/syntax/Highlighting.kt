package io.github.effectnebula.eide.core.syntax

/** Что за кусок текста. Набор намеренно мал: больше цветов — хуже читается. */
enum class TokenKind {
    Plain,
    Keyword,
    String,
    Number,
    Comment,

    /** Имя после `def`/`class` — то, что глаз ищет при беглом просмотре. */
    Declaration,
}

/** Кусок строки одного вида. Границы — индексы внутри строки, не документа. */
data class HighlightSpan(val start: Int, val end: Int, val kind: TokenKind) {
    init {
        require(start >= 0) { "start $start отрицательный" }
        require(end >= start) { "end $end меньше start $start" }
    }
}

/**
 * Результат разбора строки: куски и состояние, с которым начнётся следующая.
 *
 * Состояние — число, а не тип: его кладут в ключ кэша разметки, и там нужна
 * дешёвая склейка со строкой.
 */
data class LineHighlight(val spans: List<HighlightSpan>, val stateAfter: Int) {
    companion object {
        const val STATE_INITIAL = 0

        val EMPTY: LineHighlight = LineHighlight(emptyList(), STATE_INITIAL)
    }
}

/**
 * Разбор строки на куски для подсветки.
 *
 * Построчный и с переносимым состоянием — то есть классический лексер
 * редактора, а не разбор языка (ADR-007). Состояние нужно для того, что
 * пересекает границу строки: многострочные строки в Python, блочные
 * комментарии в C.
 *
 * Реализация обязана быть чистой функцией от `(line, stateBefore)`: результат
 * кладётся в кэш, ключом которого служит ровно эта пара.
 */
interface LineHighlighter {
    fun highlight(line: String, stateBefore: Int): LineHighlight

    companion object {
        /** Ничего не подсвечивает. Для файлов, языка которых мы не знаем. */
        val None: LineHighlighter = object : LineHighlighter {
            override fun highlight(line: String, stateBefore: Int): LineHighlight =
                LineHighlight.EMPTY
        }
    }
}
