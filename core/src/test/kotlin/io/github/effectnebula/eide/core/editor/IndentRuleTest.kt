package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Отступ новой строки.
 *
 * На телефоне это не удобство: четыре пробела руками на каждый блок — причина
 * закрыть редактор. Поэтому правило проверяется отдельно от редактора, во всех
 * случаях, где оно обязано угадать, и в тех, где оно осознанно ошибается.
 */
class IndentRuleTest {

    /** Отступ, который правило даст для Enter в конце указанной строки. */
    private fun after(source: String, rule: IndentRule = PythonIndent): String {
        val text = Rope.of(source)
        val line = text.lineCount - 1
        return rule.indentFor(text, line, text.lineEnd(line))
    }

    @Test
    fun `a colon deepens the indent`() {
        assertEquals(INDENT_UNIT, after("def шаг():"))
        assertEquals(INDENT_UNIT + INDENT_UNIT, after("def шаг():\n    if x:"))
    }

    @Test
    fun `an ordinary line keeps the indent it had`() {
        assertEquals(INDENT_UNIT, after("def шаг():\n    x = 1"))
        assertEquals("", after("import os"))
    }

    @Test
    fun `return and its relatives step back out`() {
        // После `return` блок кончился: следующая строка почти всегда снаружи.
        assertEquals("", after("def шаг():\n    return 1"))
        assertEquals("", after("def шаг():\n    pass"))
        assertEquals(INDENT_UNIT, after("def шаг():\n    if x:\n        break"))
    }

    @Test
    fun `there is nowhere to step back from the top level`() {
        assertEquals("", after("return 1"))
    }

    @Test
    fun `an indent shorter than the unit steps back to the margin`() {
        // Файл с отступом в два пробела — чужой стиль, но читать его нам.
        // `return` там выводит на нулевой уровень, а не оставляет висеть на двух.
        assertEquals("", after("def шаг():\n  return 1"))
    }

    @Test
    fun `a word merely starting with return is not return`() {
        // `returned = 1` — обычное присваивание, а не выход из блока.
        assertEquals(INDENT_UNIT, after("def шаг():\n    returned = 1"))
    }

    @Test
    fun `the text to the right of the caret does not count`() {
        // Курсор перед двоеточием: заголовка блока ещё нет, углублять не за что.
        val text = Rope.of("if x:")
        val beforeColon = text.lineEnd(0) - 1

        assertEquals("", PythonIndent.indentFor(text, 0, beforeColon))
    }

    @Test
    fun `tabs are copied as they are`() {
        // Приводить чужие табуляции к пробелам — значит переписывать чужой файл
        // при первом же нажатии Enter.
        assertEquals("\t", after("\tx = 1"))
        assertEquals("\t" + INDENT_UNIT, after("\tif x:"))
    }

    @Test
    fun `a trailing comment hides the colon from the rule`() {
        // Осознанный промах: двоеточие должно быть последним знаком строки, а
        // комментарий его закрывает. Лечится не эвристикой, а разбором, которого
        // у нас нет (ADR-007): «отрезать всё после #» сломается на `"a:# b"`.
        // Цена — один Tab там, где правило промолчало.
        assertEquals("", after("if x:  # почему"))
    }

    @Test
    fun `a colon inside a string is not seen either`() {
        // Обратная сторона той же грубости, но здесь она в нашу пользу:
        // двоеточие внутри строки почти никогда не стоит последним знаком.
        assertEquals("", after("""print("двоеточие:")"""))
    }

    @Test
    fun `an open slice deepens the indent wrongly`() {
        // А вот здесь грубость стоит нам лишнего отступа. Записано, чтобы
        // случай был известен, а не обнаружился как «иногда прыгает».
        assertEquals(INDENT_UNIT, after("x = данные[1:"))
    }

    @Test
    fun `the plain rule knows nothing about any language`() {
        assertEquals(INDENT_UNIT, after("    if x:", rule = IndentRule.CopyPrevious))
    }
}
