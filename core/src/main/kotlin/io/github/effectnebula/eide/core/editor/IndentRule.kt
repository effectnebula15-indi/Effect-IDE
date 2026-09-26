package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope

/**
 * Единица отступа: четыре пробела, а не табуляция.
 *
 * Причина не в религии, а в Python: смешение табов и пробелов там ошибка времени
 * выполнения, а не вопрос вкуса. Настройкой это станет тогда, когда появятся
 * настройки.
 */
const val INDENT_UNIT: String = "    "

/**
 * Чем отступать новую строку при нажатии Enter.
 *
 * Отдельным типом, а не веткой внутри `EditorState`, потому что правило языковое:
 * в Python отступ задаёт двоеточие, в языке со скобками — скобка. Ядру решать за
 * язык нечем, и вписывать питоновские правила в общий редактор значит навязывать
 * их всем остальным.
 */
fun interface IndentRule {

    /**
     * Отступ новой строки, если Enter нажат на строке [line] в позиции [caret].
     *
     * [caret] важен: `if x:` с курсором перед двоеточием — это ещё не заголовок
     * блока, и углублять отступ там не за что.
     */
    fun indentFor(text: Rope, line: Int, caret: Int): String

    companion object {
        /**
         * Повторить отступ текущей строки.
         *
         * Годится всегда и не врёт ни про один язык: человек сам решил, где он
         * находится, а мы только не заставляем набирать это заново.
         */
        val CopyPrevious = IndentRule { text, line, _ -> leadingWhitespace(text, line) }
    }
}

/**
 * Питоновские отступы: двоеточие углубляет, `return` и его родня выводят обратно.
 *
 * Правило грубое и таким задумано. Настоящее требует разбора: `if (a and
 * b):` с переносом внутри скобок, тройные кавычки, `else` после многострочного
 * условия. Разбора у нас нет (ADR-007), а грубое правило угадывает в подавляющем
 * большинстве строк — и там, где ошибается, человек дожимает Tab или Backspace,
 * а не переписывает строку.
 *
 * Три известных промаха, и они записаны тестами, а не обнаружатся как «иногда
 * прыгает»: `if x:  # почему` отступ не углубит (комментарий закрывает
 * двоеточие), `x = данные[1:` углубит зря, а двоеточие внутри строки правило не
 * заметит — последним знаком оно почти никогда не стоит, и здесь грубость
 * в нашу пользу. Лечится это не следующей эвристикой, а разбором: «отрезать всё
 * после #» сломается на `"a:# b"`.
 */
object PythonIndent : IndentRule {

    private val EXITS = listOf("return", "pass", "break", "continue", "raise")

    override fun indentFor(text: Rope, line: Int, caret: Int): String {
        val own = leadingWhitespace(text, line)
        val start = text.lineStart(line)

        // Смотрим только то, что осталось слева от курсора: правая часть уедет
        // на новую строку и заголовком быть перестанет.
        val head = text.substring(start, caret.coerceIn(start, text.lineEnd(line))).trim()

        if (head.endsWith(":")) return own + INDENT_UNIT

        val firstWord = head.takeWhile { !it.isWhitespace() }
        // `dropLast` сам подрезает, если отступ короче единицы, и это не просто
        // безопасно, а правильно: в файле с отступом в два пробела `return`
        // выводит на нулевой уровень, а не оставляет висеть на двух. Проверки
        // «хватает ли длины» здесь была лишней — она это и ломала.
        if (firstWord in EXITS) return own.dropLast(INDENT_UNIT.length)

        return own
    }
}

/** Пробелы и табуляции в начале строки, как они есть — без приведения к единице. */
private fun leadingWhitespace(text: Rope, line: Int): String {
    val start = text.lineStart(line)
    val end = text.lineEnd(line)
    var index = start
    while (index < end && text.charAt(index).let { it == ' ' || it == '\t' }) index++
    return text.substring(start, index)
}
