package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.EditKind
import io.github.effectnebula.eide.core.text.EditTransaction
import io.github.effectnebula.eide.core.text.Replacement

/**
 * Поиск и замена в открытом файле.
 *
 * Связывает [TextSearch] с [EditorState]: держит запрос, водит курсор по
 * совпадениям, заменяет. Сам поиск ничего не знает про курсоры, редактор —
 * ничего про регулярные выражения, и это правильно; здесь они встречаются.
 *
 * **Совпадения не собираются в список целиком.** На файле в десять мегабайт это
 * сотни тысяч объектов на каждое нажатие клавиши в строке поиска. Для подсветки
 * берётся только видимый кусок ([matchesIn]), для подписи — счёт с потолком.
 */
/** Сколько совпадений нашлось и не упёрся ли счёт в потолок. */
data class MatchCount(val value: Int, val exact: Boolean)

class SearchSession(private val state: EditorState) {

    var query: SearchQuery = SearchQuery("")
        private set

    private var search: TextSearch = TextSearch(query)

    /** Текст ошибки в регулярном выражении, если оно не компилируется. */
    val error: String? get() = search.error

    /** Текущее совпадение — то, на котором стоит курсор. */
    var current: SearchMatch? = null
        private set

    fun setQuery(query: SearchQuery) {
        this.query = query
        this.search = TextSearch(query)
        this.current = null
    }

    /**
     * Сколько совпадений в документе, но не больше [COUNT_LIMIT].
     *
     * Точный счёт на большом файле — полный проход, и делать его на каждое
     * нажатие в строке поиска нельзя. Достигнутый потолок виден в [MatchCount]:
     * интерфейс покажет «больше 500», а не соврёт числом.
     *
     * Возвращается пара, а не число с отдельным вопросом «а точное ли оно»:
     * два вызова — два прохода по документу ради одного и того же ответа.
     */
    fun count(): MatchCount {
        val found = search.findAll(state.text).take(COUNT_LIMIT).count()
        return MatchCount(found, exact = found < COUNT_LIMIT)
    }

    /**
     * Совпадения в диапазоне `[from, to)` — для подсветки видимых строк.
     *
     * Совпадение, начавшееся до [from] и заходящее внутрь, тоже попадает:
     * иначе многострочное совпадение исчезало бы при прокрутке. Его `start`
     * при этом может быть усечён — см. ниже.
     */
    fun matchesIn(from: Int, to: Int): List<SearchMatch> {
        if (query.isEmpty || from >= to) return emptyList()

        // Скан начинается не с нуля: иначе прокрутка в конец большого файла с
        // открытым поиском стоит полного прохода регулярным выражением на каждый
        // кадр — на трёх мегабайтах это сорок миллисекунд, измерено. Отступ назад
        // нужен для совпадения, начавшегося выше видимой области.
        //
        // Цена: у совпадения длиннее отступа сообщённое начало усечено до начала
        // скана. Для подсветки это незаметно — отрисовка всё равно обрезает
        // диапазон по видимым строкам, — но всякий, кто возьмёт start отсюда как
        // настоящее начало совпадения, ошибётся.
        val scanFrom = (from - LOOKBEHIND).coerceAtLeast(0)
        return search.findFrom(state.text, scanFrom)
            .dropWhile { it.end <= from }
            .takeWhile { it.start < to }
            .take(VISIBLE_LIMIT)
            .toList()
    }

    /** Переходит к следующему совпадению и выделяет его. */
    fun findNext(): Boolean {
        // Ищем от конца текущего совпадения, а не от курсора: иначе «дальше»
        // на уже найденном находит его же, и кнопка перестаёт работать.
        val from = current?.end ?: state.carets.primary.end
        return moveTo(search.findNext(state.text, from))
    }

    /** Переходит к предыдущему совпадению и выделяет его. */
    fun findPrevious(): Boolean {
        val before = current?.start ?: state.carets.primary.start
        return moveTo(search.findPrevious(state.text, before))
    }

    /**
     * Заменяет текущее совпадение и переходит к следующему.
     *
     * Без перехода человек жмёт «заменить» дважды на одном месте и не понимает,
     * почему второй раз ничего не произошло.
     */
    fun replaceCurrent(replacement: String): Boolean {
        val match = current ?: return false
        // Подстановка групп — работа поиска, а не наша: две реализации одного
        // правила разъедутся, и заметят это не сразу.
        val text = if (query.isRegex) search.expand(replacement, match) else replacement

        state.applyWithSelection(
            edit = EditTransaction(listOf(Replacement(match.start, match.end, text))),
            anchor = match.start,
            head = match.start + text.length,
            kind = EditKind.Other,
        )

        current = null
        findNext()
        return true
    }

    /** Заменяет все совпадения одной правкой. Возвращает их число. */
    fun replaceAll(replacement: String): Int {
        val edit = search.replaceAll(state.text, replacement)
        if (edit.isEmpty) return 0

        state.replaceAll(edit)
        current = null
        return edit.replacements.size
    }

    private fun moveTo(match: SearchMatch?): Boolean {
        if (match == null) {
            current = null
            return false
        }
        current = match
        // Выделяем найденное: курсор без выделения не показывает, что именно
        // нашлось, а замена работает как раз по выделению.
        state.setCarets(CaretSet.of(listOf(Caret(anchor = match.start, head = match.end))))
        return true
    }

    private companion object {
        /**
         * Потолок точного счёта. Пятьсот — заведомо больше, чем человек станет
         * разглядывать, и заведомо дешевле полного прохода по мегабайтам.
         */
        const val COUNT_LIMIT = 500

        /** Подсвечивать больше, чем помещается на экране, незачем. */
        const val VISIBLE_LIMIT = 500

        /**
         * Насколько отступать назад от видимой области при поиске совпадений
         * для подсветки.
         *
         * Четыре тысячи знаков — примерно экран очень мелкого шрифта; совпадение
         * длиннее этого не подсветится с начала. Больше отступ — дороже кадр,
         * меньше — заметнее потеря.
         */
        const val LOOKBEHIND = 4096
    }
}
