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
    fun findAll(text: Rope): Sequence<SearchMatch> = findFrom(text, 0)

    /**
     * Ленивая последовательность совпадений, начиная с позиции [from].
     *
     * Существует ради подсветки видимых строк: с ленивым `findAll` и
     * отбрасыванием начала регулярное выражение всё равно прогоняется по всему
     * тексту до нужного места, и на файле в три мегабайта это сорок миллисекунд
     * на кадр — измерено, не предположено.
     *
     * Взгляд назад за [from] выражению доступен: `Matcher.find(int)` сбрасывает
     * область на весь текст, поэтому `(?<!...)` в границах слова видит знак
     * перед началом скана и не находит слово внутри другого слова.
     */
    fun findFrom(text: Rope, from: Int): Sequence<SearchMatch> {
        val compiled = regex ?: return emptySequence()
        return compiled
            .findAll(text.asCharSequence(), from.coerceIn(0, text.length))
            .map { it.toMatch() }
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
            val found = match.toMatch()
            val newText = if (asTemplate) expand(replacement, found) else replacement
            Replacement(found.start, found.end, newText)
        }.toList()
        return EditTransaction(replacements)
    }

    /**
     * Подставляет `$1`..`$9` из совпадения.
     *
     * **Осторожно с нумерацией.** В [SearchMatch.groups] нулевой группы нет —
     * там только скобочные, — а `$1` в тексте замены означает первую скобочную.
     * Значит `$N` это `groups[N - 1]`, и это ровно то место, где ошибка на
     * единицу даёт пустую строку вместо найденного текста.
     *
     * Единственная реализация подстановки: в замене одного совпадения и в
     * замене всех она обязана вести себя одинаково.
     */
    fun expand(replacement: String, match: SearchMatch): String = buildString {
        var i = 0
        while (i < replacement.length) {
            val char = replacement[i]
            if (char != '$' || i + 1 >= replacement.length || !replacement[i + 1].isDigit()) {
                append(char)
                i++
                continue
            }

            var j = i + 1
            while (j < replacement.length && replacement[j].isDigit()) j++
            val number = replacement.substring(i + 1, j).toInt()
            if (number >= 1) append(match.groups.getOrNull(number - 1).orEmpty())
            i = j
        }
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

        /**
         * Юникодные классы символов.
         *
         * Без этого флага `\w`, `\d`, `\b` в выражении пользователя не видят
         * кириллицу: поиск `имя: (\w+)` по русскому тексту не находит ничего.
         * Человек пишет такое выражение, зная Python, где `re` работает
         * наоборот, и объяснить ему пустой результат нечем.
         *
         * Чего этот флаг **не** чинит, вопреки первому впечатлению: поиск без
         * учёта регистра по кириллице работает и без него — проверено. Ошибочный
         * вывод получился из-за пробы на Java, скомпилированной с неверной
         * кодировкой исходника: «ШАГ» и «Шаг» там превратились в разный мусор.
         * Ровно та же ловушка, что с именами файлов (см. `CLAUDE.md`).
         *
         * Цена флага: выражения ведут себя не так, как в `grep` по умолчанию.
         * Это скорее польза — так же ведёт себя `re` в Python.
         */
        const val UNICODE = "(?U)"

        fun buildRegex(query: SearchQuery): Regex? {
            if (query.isEmpty) return null
            val body = if (query.isRegex) query.pattern else Regex.escape(query.pattern)
            val bounded = if (query.wholeWord) "$WORD_BEFORE(?:$body)$WORD_AFTER" else body
            val options = if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return runCatching { Regex(UNICODE + bounded, options) }.getOrNull()
        }

    }
}
