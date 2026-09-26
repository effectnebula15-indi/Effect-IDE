package io.github.effectnebula.eide.core.project

import io.github.effectnebula.eide.core.editor.SearchQuery
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Поиск по всем файлам проекта.
 *
 * Проверяется не то, что регулярное выражение находит подстроку — это работа
 * `TextSearch`, — а обход дерева и всё, что вокруг: номера строк, потолок,
 * отмена и файлы, которые читать не надо.
 *
 * **Имена файлов здесь только латиницей.** Первая редакция называла папку
 * по-русски и падала: при `LANG=POSIX` JVM записывает такое имя на диск как
 * вопросительные знаки — та самая ловушка `sun.jnu.encoding`, описанная
 * в `CLAUDE.md`. Содержимое файлов при этом кириллическое: оно едет байтами
 * UTF-8 и от локали не зависит.
 */
class ProjectSearchTest {

    private fun project(vararg files: Pair<String, String>): ProjectTree {
        val root = Files.createTempDirectory("eide-search").toFile().apply { deleteOnExit() }
        for ((path, text) in files) {
            val file = File(root, path)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        return ProjectTree(root)
    }

    private fun search(tree: ProjectTree, pattern: String, regex: Boolean = false) =
        ProjectSearch(tree).search(SearchQuery(pattern, isRegex = regex))

    @Test
    fun `matches are found across folders`() {
        val tree = project(
            "first.py" to "искомое здесь\nи не здесь\n",
            "nested/second.py" to "снова искомое\n",
        )

        val result = search(tree, "искомое")

        assertEquals(2, result.matches.size)
        assertEquals(SearchOutcome.Complete, result.outcome)
        assertTrue(result.matches.any { it.relativePath.contains("nested") }, "вложенная папка не обойдена")
    }

    @Test
    fun `the line number and preview point at the match`() {
        val tree = project("file.py" to "первая\nвторая с искомым\nтретья\n")

        val match = search(tree, "искомым").matches.single()

        assertEquals(1, match.line, "номер строки считается с нуля")
        assertEquals("вторая с искомым", match.preview)
        assertEquals("вторая с ".length, match.column)
    }

    @Test
    fun `a match on the last line without a trailing newline is found`() {
        // Классическое место ошибки на единицу: последняя строка без перевода.
        val tree = project("file.py" to "первая\nпоследняя с искомым")

        val match = search(tree, "искомым").matches.single()

        assertEquals(1, match.line)
        assertEquals("последняя с искомым", match.preview)
    }

    @Test
    fun `binary files are left alone`() {
        // Нулевой байт — тот же признак, которым пользуется git.
        val root = Files.createTempDirectory("eide-search-bin").toFile().apply { deleteOnExit() }
        File(root, "picture.dat").writeBytes(byteArrayOf(0, 1, 2) + "искомое".toByteArray())
        File(root, "text.py").writeText("искомое")

        val result = ProjectSearch(ProjectTree(root)).search(SearchQuery("искомое"))

        assertEquals(1, result.matches.size, "совпадение вытащено из двоичного файла")
        assertEquals(1, result.filesSkipped)
    }

    @Test
    fun `huge files are skipped`() {
        val tree = project("big.py" to "искомое\n" + "x".repeat(4096))

        val result = ProjectSearch(tree).search(SearchQuery("искомое"), maxFileBytes = 1024)

        assertTrue(result.matches.isEmpty())
        assertEquals(1, result.filesSkipped)
    }

    @Test
    fun `the limit stops the search`() {
        val tree = project("many.py" to "искомое\n".repeat(50))

        val result = ProjectSearch(tree).search(SearchQuery("искомое"), limit = 10)

        assertEquals(10, result.matches.size)
        assertEquals(SearchOutcome.LimitReached, result.outcome)
    }

    @Test
    fun `cancellation stops the walk`() {
        val tree = project(
            "one.py" to "искомое",
            "two.py" to "искомое",
            "three.py" to "искомое",
        )

        // Отмена сразу: ни одного файла прочитать не успеваем.
        val result = ProjectSearch(tree).search(SearchQuery("искомое"), isCancelled = { true })

        assertEquals(SearchOutcome.Cancelled, result.outcome)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `a broken regular expression finds nothing instead of throwing`() {
        val tree = project("file.py" to "текст")

        val result = ProjectSearch(tree).search(SearchQuery("(незакрытая", isRegex = true))

        assertTrue(result.matches.isEmpty())
        assertEquals(SearchOutcome.Complete, result.outcome)
    }

    @Test
    fun `an empty query finds nothing`() {
        val tree = project("file.py" to "текст")

        assertTrue(search(tree, "").matches.isEmpty())
    }
}
