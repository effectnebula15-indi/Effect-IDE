package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope

/** Один шаг пути: строка заголовка и то, что на ней написано. */
data class Crumb(val line: Int, val title: String)

/**
 * Путь до строки: цепочка объемлющих `class` и `def`.
 *
 * Зачем: на телефоне на экране помещается три десятка строк, и «где я сейчас»
 * по ним не восстановить. На десктопе то же самое, только менее остро.
 *
 * **Считается по отступам, а не по дереву разбора** — как и свёртка, и по той же
 * причине: в Python отступ и есть структура блока, а дерева у нас нет (ADR-007).
 * Отсюда и граница применимости: для языка со скобками этот расчёт даст ерунду,
 * поэтому он назван `PythonOutline`, а не «крошки вообще».
 *
 * Цена: подъём от строки вверх по файлу. Останавливаемся на первом же нулевом
 * отступе — выше него объемлющего блока быть не может, — а на случай файла, где
 * нулевого отступа так и не встретилось, есть потолок [MAX_SCAN]. Без него
 * курсор в конце десятимегабайтного файла стоил бы прохода по всему файлу на
 * каждое движение.
 */
object PythonOutline {

    fun crumbsAt(text: Rope, line: Int): List<Crumb> {
        if (line !in 0 until text.lineCount) return emptyList()

        val crumbs = ArrayList<Crumb>()
        var limit = Int.MAX_VALUE
        var index = line
        var scanned = 0

        while (index >= 0 && scanned < MAX_SCAN) {
            scanned++
            val indent = IndentFolding.indentOf(text, index)

            if (indent != IndentFolding.BLANK && indent < limit) {
                // Снаружи может лежать только блок с меньшим отступом — даже если
                // эта строка сама заголовком не была. Иначе `else:` на одном
                // уровне с `if` попал бы в путь как объемлющий.
                limit = indent
                headerAt(text, index)?.let { crumbs += Crumb(index, it) }

                // Выход на нулевом отступе — цена, а не правило: на ответ он не
                // влияет (меньше нуля отступа не бывает, и добавить выше уже
                // нечего), зато без него курсор в конце файла стоит подъёма до
                // самого верха на каждое движение. Мутация «убрать» тесты не
                // роняет — и не должна; проверять здесь надо было бы счётчик
                // пройденных строк, а заводить его ради одной ветки дороже, чем
                // написать эту оговорку.
                if (indent == 0) break
            }
            index--
        }

        return crumbs.asReversed()
    }

    /** Заголовок блока или null. Питоновский: `def`, `async def`, `class`. */
    private fun headerAt(text: Rope, line: Int): String? {
        val start = text.lineStart(line)
        val end = text.lineEnd(line)
        val head = text.substring(start, minOf(end, start + MAX_HEADER)).trim()

        val keyword = KEYWORDS.firstOrNull { head.startsWith(it) } ?: return null
        if (head.length == keyword.length) return null

        // Двоеточие в конце — часть синтаксиса, а не имени. Для длинной сигнатуры
        // обрезаем: путь должен помещаться в строку, а не быть ею.
        val title = head.removeSuffix(":").trim()
        return if (title.length > MAX_TITLE) title.take(MAX_TITLE) + "…" else title
    }

    private val KEYWORDS = listOf("def ", "async def ", "class ")

    /**
     * Потолок подъёма. Две тысячи строк — это заведомо больше любой осмысленной
     * вложенности; упёршись в него, крошки просто окажутся неполными, а не
     * неверными.
     */
    const val MAX_SCAN = 2_000

    /** Докуда читать строку заголовка. Дальше — не сигнатура, а данные. */
    private const val MAX_HEADER = 512

    private const val MAX_TITLE = 60
}
