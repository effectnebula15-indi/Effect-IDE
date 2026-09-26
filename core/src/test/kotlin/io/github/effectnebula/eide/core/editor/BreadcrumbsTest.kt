package io.github.effectnebula.eide.core.editor

import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Путь до строки по отступам.
 *
 * Проверяется не «нашёлся ли какой-нибудь заголовок», а что в пути ровно те
 * блоки, внутри которых строка лежит: лишний шаг врёт о вложенности, а
 * пропущенный — о принадлежности.
 */
class BreadcrumbsTest {

    private val source = Rope.of(
        """
        import os


        class Игра:

            def __init__(self, экран):
                self.экран = экран

            def шаг(self, время):
                if время > 0:
                    self.двигать()
                else:
                    self.стоять()

            @property
            def счёт(self):
                return self._счёт


        def main():
            Игра(None)
        """.trimIndent()
    )

    private fun path(line: Int) = PythonOutline.crumbsAt(source, line).map { it.title }

    private fun lineOf(needle: String): Int {
        val text = source.substring(0, source.length)
        val index = text.split("\n").indexOfFirst { it.contains(needle) }
        assertTrue(index >= 0, "в образце нет строки с «$needle»")
        return index
    }

    @Test
    fun `a line inside a method names the class and the method`() {
        assertEquals(listOf("class Игра", "def шаг(self, время)"), path(lineOf("self.двигать()")))
    }

    @Test
    fun `a header names itself last`() {
        // Курсор на самой строке `def шаг` — она объемлющий блок для своего тела
        // и обязана быть в пути, иначе крошки мигают при движении курсора вниз.
        assertEquals(listOf("class Игра", "def шаг(self, время)"), path(lineOf("def шаг")))
    }

    @Test
    fun `a branch at the same level is not a step of the path`() {
        // `else:` лежит на одном уровне с `if`, а не внутри него.
        assertEquals(listOf("class Игра", "def шаг(self, время)"), path(lineOf("self.стоять()")))
    }

    @Test
    fun `a top level line has no path`() {
        assertEquals(emptyList(), path(lineOf("import os")))
    }

    @Test
    fun `a top level function is the whole path for its body`() {
        assertEquals(listOf("def main()"), path(lineOf("Игра(None)")))
    }

    @Test
    fun `a decorator does not become a step`() {
        assertEquals(listOf("class Игра", "def счёт(self)"), path(lineOf("return self._счёт")))
    }

    @Test
    fun `a blank line keeps the path of the code above it`() {
        // На пустой строке правильного ответа нет: что там окажется, зависит от
        // того, с каким отступом человек начнёт печатать. Выбран путь строки
        // выше — тогда крошки не мигают, пока курсор идёт по пустым строкам
        // между методами. Первая редакция теста ждала обратного: что пустая
        // строка между методами покажет только класс.
        val blank = lineOf("self.экран = экран") + 1
        assertEquals(listOf("class Игра", "def __init__(self, экран)"), path(blank))
    }

    @Test
    fun `a line outside the document has no path`() {
        assertEquals(emptyList(), path(-1))
        assertEquals(emptyList(), path(source.lineCount))
    }

    @Test
    fun `crumbs point at the lines they were taken from`() {
        val crumbs = PythonOutline.crumbsAt(source, lineOf("self.двигать()"))

        assertEquals(lineOf("class Игра"), crumbs.first().line)
        assertEquals(lineOf("def шаг"), crumbs.last().line)
    }

    @Test
    fun `a long signature is cut, not wrapped`() {
        val long = Rope.of("def очень_длинное_имя(" + "аргумент, ".repeat(20) + "):\n    pass\n")

        val title = PythonOutline.crumbsAt(long, 1).single().title

        assertTrue(title.endsWith("…"), "длинная сигнатура обязана быть обрезана: «$title»")
        assertTrue(title.length <= 61, "обрезка не сработала: ${title.length} знаков")
    }

    @Test
    fun `the climb is bounded`() {
        // Файл, в котором нулевого отступа выше курсора нет вовсе: подъём должен
        // упереться в потолок, а не пройти миллион строк.
        val deep = Rope.of("    строка\n".repeat(PythonOutline.MAX_SCAN * 2))

        assertEquals(emptyList(), PythonOutline.crumbsAt(deep, deep.lineCount - 1))
    }
}
