package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Replacement
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.core.text.asCharSequence

/** Что и как искать. */
data class SearchQuery(
    val pattern: String,
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
) {
    val isEmpty: Boolean get() = pattern.isEmpty()
}

/** Найденное совпадение: диапазон в документе и группы, если искали регулярным выражением. */
data class SearchMatch(val start: Int, val end: Int, val groups: List<String?>)

/**
 * Поиск по документу.
 *
 * Работает поверх [asCharSequence], то есть не материализует документ в одну строку:
 * искать в файле на десять мегабайт, скопировав его целиком в память ради поиска, —
 * плохой способ экономить своё время.
 */
class TextSearch(private val query: SearchQuery) {

    /** null, если запрос пуст или регулярное выражение не компилируется. */
    val regex: Regex? = buildRegex(query)

    val error: String? = if (query.isEmpty || regex != null) null else "неверное регулярное выражение"

    /** Ленивая последовательность совпадений от начала документа. */
    fun findAll(text: Rope): Sequence<SearchMatch> {
        val compiled = regex ?: return emptySequence()
        return compiled.findAll(text.asCharSequence()).map { it.toMatch() }
    }

    /**
     * Ближайшее совпадение, начинающееся не раньше [from].
     * При [wrap] поиск продолжается с начала документа.
     */
    fun findNext(text: Rope, from: Int, wrap: Boolean = true): SearchMatch? {
        val compiled = regex ?: return null
        val sequence = text.asCharSequence()
        compiled.find(sequence, from.coerceIn(0, text.length))?.let { return it.toMatch() }
        if (!wrap) return null
        return compiled.find(sequence, 0)?.toMatch()
    }

    /** Ближайшее совпадение, заканчивающееся не позже [before]. */
    fun findPrevious(text: Rope, before: Int, wrap: Boolean = true): SearchMatch? {
        val compiled = regex ?: return null
        val sequence = text.asCharSequence()

        var last: SearchMatch? = null
        for (match in compiled.findAll(sequence)) {
            if (match.range.last + 1 > before) break
            last = match.toMatch()
        }
        if (last != null || !wrap) return last

        // Обход по кругу: берём самое последнее совпадение в документе.
        return compiled.findAll(sequence).lastOrNull()?.toMatch()
    }

    /**
     * Правка, заменяющая все совпадения.
     *
     * В [replacement] работают ссылки на группы вида `$1`, если искали регулярным
     * выражением. При обычном поиске текст подставляется как есть — иначе доллар
     * в заменяемом тексте молча превратился бы в ссылку на группу.
     */
    fun replaceAll(text: Rope, replacement: String, asTemplate: Boolean = query.isRegex): EditTransaction {
        val compiled = regex ?: return EditTransaction(emptyList())
        val replacements = compiled.findAll(text.asCharSequence()).map { match ->
            val newText = if (asTemplate) expandTemplate(replacement, match) else replacement
            Replacement(match.range.first, match.range.last + 1, newText)
        }.toList()
        return EditTransaction(replacements)
    }

    private fun MatchResult.toMatch(): SearchMatch =
        SearchMatch(
            start = range.first,
            end = range.last + 1,
            groups = groupValues.drop(1),
        )

    private companion object {
        /**
         * Границы слова задаются явно, а не через `\b`.
         *
         * `\b` в Java опирается на `\w`, куда кириллица не входит без отдельного
         * флага: поиск слова «шаг» с включённой опцией «слово целиком» просто не
         * находил бы ничего.
         */
        const val WORD_BEFORE = "(?<![\\p{L}\\p{N}_])"
        const val WORD_AFTER = "(?![\\p{L}\\p{N}_])"

        fun buildRegex(query: SearchQuery): Regex? {
            if (query.isEmpty) return null
            val body = if (query.isRegex) query.pattern else Regex.escape(query.pattern)
            val pattern = if (query.wholeWord) "$WORD_BEFORE(?:$body)$WORD_AFTER" else body
            val options = if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return runCatching { Regex(pattern, options) }.getOrNull()
        }

        fun expandTemplate(template: String, match: MatchResult): String = buildString {
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c == '$' && i + 1 < template.length && template[i + 1].isDigit()) {
                    var j = i + 1
                    while (j < template.length && template[j].isDigit()) j++
                    val group = template.substring(i + 1, j).toInt()
                    append(match.groupValues.getOrNull(group).orEmpty())
                    i = j
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
