package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope

/**
 * Свёртываемый участок: заголовок [header] остаётся виден, строки
 * `(header, last]` прячутся.
 *
 * Границы именно такие, потому что свёрнутый блок должен читаться: видно
 * `def имя(...):`, не видно тела.
 */
data class FoldRegion(val header: Int, val last: Int) {
    /** Сколько строк спрячется. Ноль означает, что сворачивать нечего. */
    val hiddenCount: Int get() = last - header
}

/**
 * Поиск свёртываемых участков по отступам.
 *
 * По отступам, а не по синтаксическому дереву: в Python отступ **и есть**
 * структура блока, а дерева у нас нет и не будет до tree-sitter (ADR-007).
 * Для языков со скобками это даст осмысленный, но не идеальный результат —
 * закрывающая скобка на отдельной строке останется снаружи блока.
 *
 * Пустые строки внутри блока не разрывают его: между методами класса пустая
 * строка — норма, а не конец класса.
 */
object IndentFolding {

    /**
     * Все свёртываемые участки документа.
     *
     * Стоит прохода по всему файлу, поэтому на кадре не зовётся: отрисовке
     * хватает [isFoldable] по видимым строкам, а [regionAt] считается один раз
     * при сворачивании.
     */
    fun regions(text: Rope): List<FoldRegion> {
        val indents = IntArray(text.lineCount) { indentOf(text, it) }
        val regions = ArrayList<FoldRegion>()

        for (header in indents.indices) {
            if (indents[header] == BLANK) continue
            val last = lastLineOfBlock(header, indents.size, indents[header]) { indents[it] }
            if (last > header) regions += FoldRegion(header, last)
        }
        return regions
    }

    /**
     * Начинается ли на этой строке свёртываемый участок.
     *
     * Дёшево: смотрит вперёд только до первой непустой строки. Этого достаточно,
     * чтобы решить, рисовать ли треугольник в гаттере, а полную протяжённость
     * блока считать до нажатия незачем.
     */
    fun isFoldable(text: Rope, header: Int): Boolean {
        val own = indentOf(text, header)
        if (own == BLANK) return false

        var line = header + 1
        while (line < text.lineCount) {
            val indent = indentOf(text, line)
            if (indent != BLANK) return indent > own
            line++
        }
        return false
    }

    /**
     * Участок, начинающийся на этой строке, либо null.
     *
     * Считается по нажатию, а не на кадре: в худшем случае это проход до конца
     * файла — ровно один раз на сворачивание.
     */
    fun regionAt(text: Rope, header: Int): FoldRegion? {
        val own = indentOf(text, header)
        if (own == BLANK) return null

        val last = lastLineOfBlock(header, text.lineCount, own) { indentOf(text, it) }
        return if (last > header) FoldRegion(header, last) else null
    }

    /** Последняя строка блока: общий проход для обоих способов счёта. */
    private inline fun lastLineOfBlock(
        header: Int,
        lineCount: Int,
        headerIndent: Int,
        indentOf: (Int) -> Int,
    ): Int {
        var last = header
        var line = header + 1
        while (line < lineCount) {
            val indent = indentOf(line)
            // Пустая строка сама по себе блок не закрывает, но и не продлевает:
            // закрывающие пустые строки в блок не входят.
            if (indent == BLANK) {
                line++
                continue
            }
            if (indent <= headerIndent) break
            last = line
            line++
        }
        return last
    }

    /** Отступ строки в знаках, либо [BLANK] для пустой и состоящей из пробелов. */
    private fun indentOf(text: Rope, line: Int): Int {
        val start = text.lineStart(line)
        val end = text.lineEnd(line)
        // Берём кусок строки одним вызовом: посимвольное обращение к rope стоит
        // спуска по дереву на каждый знак, а строк здесь — весь файл.
        val head = text.substring(start, minOf(end, start + MAX_INDENT))

        var indent = 0
        for (char in head) {
            when (char) {
                ' ' -> indent++
                // Табуляция считается за место до следующей позиции табуляции:
                // смешанные отступы иначе дают бессмысленную вложенность.
                '\t' -> indent += TAB_WIDTH - indent % TAB_WIDTH
                else -> return indent
            }
        }
        // Кончился не текст, а взятый кусок: строка из одних пробелов длиннее
        // MAX_INDENT считается пустой, и это ровно то, чего от неё ждут.
        return if (end > start && head.length == MAX_INDENT) indent else BLANK
    }

    const val BLANK = -1
    const val TAB_WIDTH = 4

    /**
     * Докуда считаем отступ. Строка с отступом в две сотни знаков — не код,
     * а данные, и вкладывать её ещё глубже незачем.
     */
    const val MAX_INDENT = 256
}

/**
 * Что свёрнуто сейчас и как из-за этого сдвинулись номера строк.
 *
 * Две системы координат живут рядом: **строка документа** — то, что в файле,
 * **видимая строка** — то, что на экране. Свёртка делает их разными, и почти
 * все ошибки свёртки — это перепутанные координаты: курсор уезжает не туда,
 * прокрутка считает не то, гаттер показывает чужой номер.
 *
 * Поэтому перевод живёт здесь, в ядре, с обычными тестами, а не в отрисовке,
 * где его можно проверить только глазами.
 *
 * **Про цену.** Перевод спрашивают на каждую видимую строку каждого кадра.
 * Наивная реализация — пройти все строки документа и посчитать спрятанные —
 * стоит длины файла на строку экрана, то есть кадр в сотни миллисекунд на
 * большом файле. Поэтому свёрнутые участки держатся слитыми в непересекающиеся
 * отрезки с накопленными суммами, и оба перевода стоят двоичного поиска.
 */
class FoldState {

    /** Свёрнутые участки по строке-заголовку — источник правды. */
    private val folded = LinkedHashMap<Int, FoldRegion>()

    /** Слитые непересекающиеся отрезки спрятанных строк, по возрастанию. */
    private var hidden: List<IntRange> = emptyList()

    /** `before[i]` — сколько строк спрятано в отрезках до `hidden[i]`. */
    private var before: IntArray = IntArray(0)

    val isEmpty: Boolean get() = folded.isEmpty()

    fun foldedRegions(): List<FoldRegion> = folded.values.toList()

    fun isFolded(header: Int): Boolean = folded.containsKey(header)

    fun fold(region: FoldRegion) {
        if (region.hiddenCount <= 0) return
        folded[region.header] = region
        rebuild()
    }

    fun unfold(header: Int) {
        if (folded.remove(header) != null) rebuild()
    }

    fun toggle(region: FoldRegion) {
        if (isFolded(region.header)) unfold(region.header) else fold(region)
    }

    fun clear() {
        folded.clear()
        rebuild()
    }

    /** Спрятана ли строка внутри какого-нибудь свёрнутого участка. */
    fun isHidden(line: Int): Boolean = indexOfRangeAt(line) >= 0

    /**
     * Сколько строк видно, если всего в документе [lineCount].
     *
     * Вложенные участки не складываются: строка, спрятанная дважды, прячется
     * один раз. Наивная сумма `hiddenCount` дала бы отрицательную высоту
     * документа на вложенных блоках — а вложенные блоки в Python это норма.
     *
     * Здесь намеренно нет защитного `coerceAtLeast(1)`: он был, и он же скрыл
     * поломку слияния отрезков от теста. Обрезать бессмыслицу — работа того,
     * кто рисует, а не того, кто считает.
     */
    fun visibleLineCount(lineCount: Int): Int = lineCount - hiddenBefore(lineCount)

    /** Номер строки документа по видимому номеру. */
    fun documentLine(visual: Int, lineCount: Int): Int {
        val last = maxOf(0, lineCount - 1)
        if (hidden.isEmpty()) return visual.coerceIn(0, last)

        // Последний отрезок, начало которого видимый номер уже миновал.
        var low = 0
        var high = hidden.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) / 2
            val visibleBeforeStart = hidden[middle].first - before[middle]
            if (visibleBeforeStart <= visual) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        if (found < 0) return visual.coerceIn(0, last)
        val range = hidden[found]
        val length = range.last - range.first + 1
        return (visual + before[found] + length).coerceIn(0, last)
    }

    /**
     * Видимый номер строки документа.
     *
     * У спрятанной строки своего номера нет — возвращается номер её заголовка:
     * курсор, оказавшийся внутри свёрнутого блока, должен показываться на самом
     * блоке, а не пропадать.
     */
    fun visualLine(document: Int, lineCount: Int): Int {
        val last = maxOf(0, lineCount - 1)
        if (hidden.isEmpty()) return document.coerceIn(0, last)

        val index = indexOfRangeAt(document)
        val visible = if (index >= 0) hidden[index].first - 1 else document
        return (visible - hiddenBefore(visible)).coerceAtLeast(0)
    }

    /** Свёрнутый участок, внутри которого лежит строка, либо null. */
    fun enclosing(line: Int): FoldRegion? =
        folded.values.filter { line > it.header && line <= it.last }.minByOrNull { it.header }

    /** Сколько строк спрятано строго до [line]. */
    fun hiddenBefore(line: Int): Int {
        if (hidden.isEmpty()) return 0

        var low = 0
        var high = hidden.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (hidden[middle].first < line) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        if (found < 0) return 0
        val range = hidden[found]
        val inside = (line - range.first).coerceAtMost(range.last - range.first + 1)
        return before[found] + inside
    }

    /** Индекс отрезка, содержащего строку, либо -1. */
    private fun indexOfRangeAt(line: Int): Int {
        var low = 0
        var high = hidden.size - 1
        while (low <= high) {
            val middle = (low + high) / 2
            val range = hidden[middle]
            when {
                line < range.first -> high = middle - 1
                line > range.last -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }

    /**
     * Пересобирает слитые отрезки.
     *
     * Слияние обязательно: вложенные блоки дают пересекающиеся участки, а вся
     * арифметика ниже верна только для непересекающихся.
     */
    private fun rebuild() {
        if (folded.isEmpty()) {
            hidden = emptyList()
            before = IntArray(0)
            return
        }

        val sorted = folded.values
            .map { it.header + 1..it.last }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }

        val merged = ArrayList<IntRange>(sorted.size)
        for (range in sorted) {
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) {
                merged[merged.size - 1] = previous.first..maxOf(previous.last, range.last)
            } else {
                merged += range
            }
        }

        val sums = IntArray(merged.size)
        var total = 0
        for (index in merged.indices) {
            sums[index] = total
            total += merged[index].last - merged[index].first + 1
        }

        hidden = merged
        before = sums
    }
}
