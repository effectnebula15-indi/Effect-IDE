package io.github.effectnebula.eide.core.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Лексер Python.
 *
 * Половина проверок — про границы кусков: подсветка, съехавшая на символ,
 * выглядит как поломка шрифта, а не как ошибка в разборе, и причину ищут не там.
 */
class PythonHighlighterTest {

    private fun spans(line: String, state: Int = LineHighlight.STATE_INITIAL) =
        PythonHighlighter.highlight(line, state).spans

    private fun stateAfter(line: String, state: Int = LineHighlight.STATE_INITIAL) =
        PythonHighlighter.highlight(line, state).stateAfter

    /** Куски в виде «текст:вид» — так ошибку в границах видно сразу. */
    private fun described(line: String, state: Int = LineHighlight.STATE_INITIAL) =
        spans(line, state).map { "${line.substring(it.start, it.end)}:${it.kind}" }

    // --- ключевые слова ---------------------------------------------------------

    @Test
    fun `keywords are marked`() {
        assertEquals(listOf("if:Keyword", "in:Keyword"), described("if x in xs:"))
    }

    @Test
    fun `a keyword inside a longer word is not a keyword`() {
        // «information» начинается с «in», «assertion» — с «assert»: наивный
        // поиск подстроки покрасил бы половину слова.
        assertEquals(listOf("1:Number"), described("information = 1"))
        assertEquals(emptyList(), described("assertion"))
    }

    @Test
    fun `a keyword right after a bracket is still a keyword`() {
        assertEquals(listOf("not:Keyword"), described("(not x)"))
    }

    @Test
    fun `constants are marked like keywords`() {
        assertEquals(listOf("None:Keyword", "or:Keyword", "True:Keyword"), described("value = None or True"))
    }

    // --- объявления -------------------------------------------------------------

    @Test
    fun `the name after def is marked separately`() {
        assertEquals(
            listOf("def:Keyword", "шаг_первый:Declaration"),
            described("def шаг_первый(значение):"),
        )
    }

    @Test
    fun `the name after class is marked separately`() {
        assertEquals(listOf("class:Keyword", "Точка:Declaration"), described("class Точка:"))
    }

    @Test
    fun `def without a name does not invent one`() {
        assertEquals(listOf("def:Keyword"), described("def"))
        assertEquals(listOf("def:Keyword"), described("def ("))
    }

    @Test
    fun `only def and class produce a declaration`() {
        assertEquals(listOf("return:Keyword"), described("return значение"))
    }

    // --- строки -----------------------------------------------------------------

    @Test
    fun `a double quoted string is one span`() {
        assertEquals(listOf("\"привет\":String"), described("x = \"привет\""))
    }

    @Test
    fun `an escaped quote does not end the string`() {
        assertEquals(listOf("\"a\\\"b\":String"), described("x = \"a\\\"b\""))
    }

    @Test
    fun `an escaped backslash before a quote does end the string`() {
        val line = "x = \"a\\\\\" + y"
        assertEquals(listOf("\"a\\\\\":String"), described(line))
    }

    @Test
    fun `an unterminated single quoted string ends with the line`() {
        assertEquals(LineHighlight.STATE_INITIAL, stateAfter("x = \"забыли закрыть"))
    }

    @Test
    fun `a keyword inside a string is not highlighted`() {
        assertEquals(listOf("\"if and or\":String"), described("x = \"if and or\""))
    }

    // --- многострочные строки ----------------------------------------------------

    @Test
    fun `an opened triple quote carries state to the next line`() {
        val opened = stateAfter("\"\"\"начало докстринга")

        assertTrue(opened != LineHighlight.STATE_INITIAL, "состояние не перенеслось")
        assertEquals(listOf("продолжение:String"), described("продолжение", opened))
    }

    @Test
    fun `a triple quote closes on a later line`() {
        val opened = stateAfter("\"\"\"начало")

        assertEquals(
            LineHighlight.STATE_INITIAL,
            PythonHighlighter.highlight("конец\"\"\" и код", opened).stateAfter,
        )
        assertEquals(listOf("конец\"\"\":String"), described("конец\"\"\" и код", opened))
    }

    @Test
    fun `code after a closed triple quote is highlighted again`() {
        val opened = stateAfter("'''начало")

        assertEquals(listOf("конец''':String", "if:Keyword"), described("конец''' if x", opened))
    }

    @Test
    fun `quotes of different kinds do not close each other`() {
        val opened = stateAfter("'''начало")

        assertTrue(opened != LineHighlight.STATE_INITIAL)
        assertEquals(opened, stateAfter("тут \"\"\" не закрывает", opened))
    }

    @Test
    fun `a triple quote opened and closed on one line carries no state`() {
        assertEquals(LineHighlight.STATE_INITIAL, stateAfter("x = \"\"\"одна строка\"\"\""))
        assertEquals(listOf("\"\"\"одна строка\"\"\":String"), described("x = \"\"\"одна строка\"\"\""))
    }

    // --- комментарии и числа ------------------------------------------------------

    @Test
    fun `a comment runs to the end of the line`() {
        assertEquals(listOf("# if and or:Comment"), described("x = 1  # if and or").drop(1))
    }

    @Test
    fun `a hash inside a string is not a comment`() {
        assertEquals(listOf("\"# не комментарий\":String"), described("x = \"# не комментарий\""))
    }

    @Test
    fun `numbers are marked, including underscores and dots`() {
        assertEquals(listOf("1_000_000:Number"), described("x = 1_000_000"))
        assertEquals(listOf("3.14:Number"), described("x = 3.14"))
        assertEquals(listOf("0xFF:Number"), described("x = 0xFF"))
    }

    @Test
    fun `a digit inside an identifier does not start a number`() {
        assertEquals(listOf("0:Number"), described("шаг2 = 0"))
    }

    // --- границы ------------------------------------------------------------------

    @Test
    fun `spans never leave the line`() {
        val lines = listOf("def f(): pass", "x = \"незакрытая", "'''", "# комментарий", "", "    ")
        for (line in lines) {
            for (span in spans(line)) {
                assertTrue(span.start >= 0 && span.end <= line.length, "кусок вне строки: $line")
            }
        }
    }

    @Test
    fun `spans do not overlap and go in order`() {
        val line = "def f(x): return \"a\" + 1  # хвост"
        var previousEnd = 0
        for (span in spans(line)) {
            assertTrue(span.start >= previousEnd, "куски пересекаются или идут не по порядку: $line")
            previousEnd = span.end
        }
    }

    @Test
    fun `an empty line gives nothing`() {
        assertEquals(emptyList(), spans(""))
        assertEquals(LineHighlight.STATE_INITIAL, stateAfter(""))
    }
}
