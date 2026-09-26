package io.github.effectnebula.eide.core.lang

import io.github.effectnebula.eide.core.editor.INDENT_UNIT
import io.github.effectnebula.eide.core.text.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Выбор языка по имени файла.
 *
 * Раньше это решалось в трёх местах — в ядре редактора и в двух точках сборки, —
 * и списки расширений там уже начали расходиться. Проверяется поэтому не «знает
 * ли реестр про Python», а что незнакомый файл получает поведение, которое ему
 * не навредит.
 */
class LanguagesTest {

    private fun indentAfter(name: String, line: String): String {
        val text = Rope.of(line)
        return Languages.forFile(name).indent.indentFor(text, 0, text.lineEnd(0))
    }

    @Test
    fun `python is found by extension, in any case`() {
        assertEquals("python", Languages.forFile("main.py").id)
        assertEquals("python", Languages.forFile("Главный.PY").id)
        assertEquals("python", Languages.forFile("окно.pyw").id)
    }

    @Test
    fun `an unknown language gets rules that cannot hurt it`() {
        // Питоновский отступ в файле на C сделал бы хуже, чем его отсутствие:
        // после `if (x) {` двоеточия нет, а после `int f():` — есть.
        assertEquals("", indentAfter("main.c", "if (x) {"))
        assertNull(Languages.forFile("main.c").outline)
    }

    @Test
    fun `a file without an extension is not python`() {
        assertEquals("plain", Languages.forFile("Makefile").id)
        assertEquals("plain", Languages.forFile("LICENSE").id)
    }

    @Test
    fun `a name that merely contains py is not python`() {
        // «pyproject.toml» — не Python, хотя начинается с тех же букв.
        assertEquals("plain", Languages.forFile("pyproject.toml").id)
        assertEquals("plain", Languages.forFile("python").id)
    }

    @Test
    fun `python brings both the indent rule and the outline`() {
        assertEquals(INDENT_UNIT, indentAfter("main.py", "def шаг():"))
        assertNotNull(Languages.forFile("main.py").outline)
    }
}
