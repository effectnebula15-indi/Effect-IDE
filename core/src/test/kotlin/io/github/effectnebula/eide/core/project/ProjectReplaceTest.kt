package io.github.effectnebula.eide.core.project

import io.github.effectnebula.eide.core.editor.SearchQuery
import io.github.effectnebula.eide.platform.GraphemeBreaker
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Замена по всем файлам проекта.
 *
 * Здесь проверяется не поиск подстроки — это работа `TextSearch`, — а то, что
 * замена пишет ровно те файлы, в которые заходил бы поиск, и ровно в том виде,
 * в каком они были: с их переносами строк и с несохранёнными правками человека,
 * а не поверх них.
 *
 * Имена файлов латиницей: ловушка `sun.jnu.encoding` из `CLAUDE.md`.
 */
class ProjectReplaceTest {

    private object Breaker : GraphemeBreaker {
        override fun next(line: CharSequence, from: Int): Int = minOf(from + 1, line.length)
        override fun previous(line: CharSequence, from: Int): Int = maxOf(from - 1, 0)
    }

    private fun project(vararg files: Pair<String, String>): File {
        val root = Files.createTempDirectory("eide-replace").toFile().apply { deleteOnExit() }
        for ((path, text) in files) {
            val file = File(root, path)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        return root
    }

    private fun replace(root: File, pattern: String, replacement: String, regex: Boolean = false) =
        ProjectReplace(ProjectTree(root)).replace(SearchQuery(pattern, isRegex = regex), replacement)

    /**
     * Полный порядок замены, как его обязан вести интерфейс: сохранить буферы,
     * заменить на диске, повторить в буферах. Обёрнут функцией не для краткости,
     * а потому что пропущенный первый шаг стирает несохранённое, и в тестах этот
     * порядок должен быть ровно один.
     */
    private fun replaceEverywhere(
        workspace: Workspace,
        root: File,
        pattern: String,
        replacement: String,
    ): Pair<ProjectReplaceResult, List<ReplacedFile>> {
        workspace.saveModified()
        val query = SearchQuery(pattern)
        val onDisk = ProjectReplace(ProjectTree(root)).replace(query, replacement)
        return onDisk to replayInBuffers(workspace, query, replacement)
    }

    @Test
    fun `replaces across folders and counts every file`() {
        val root = project(
            "first.py" to "иголка и ещё иголка\n",
            "nested/second.py" to "сено\nиголка\n",
            "third.py" to "здесь ничего нет\n",
        )

        val result = replace(root, "иголка", "гвоздь")

        assertEquals(3, result.replaced)
        assertEquals(2, result.changed.size, "третий файл трогать было незачем")
        assertEquals("гвоздь и ещё гвоздь\n", File(root, "first.py").readText())
        assertEquals("сено\nгвоздь\n", File(root, "nested/second.py").readText())
        assertEquals("здесь ничего нет\n", File(root, "third.py").readText())
    }

    @Test
    fun `windows line endings survive the replacement`() {
        // Внутри редактора переносы всегда `\n`; на диске файл обязан остаться
        // таким, каким был, иначе замена одного слова переписывает весь файл
        // и git показывает изменение в каждой строке.
        val root = project("crlf.py" to "иголка\r\nвторая строка\r\n")

        replace(root, "иголка", "гвоздь")

        assertEquals("гвоздь\r\nвторая строка\r\n", File(root, "crlf.py").readText())
    }

    @Test
    fun `groups from a regex reach the replacement`() {
        val root = project("main.py" to "имя: Иван\nимя: Пётр\n")

        val result = replace(root, "имя: (\\w+)", "$1 — имя", regex = true)

        assertEquals(2, result.replaced)
        assertEquals("Иван — имя\nПётр — имя\n", File(root, "main.py").readText())
    }

    @Test
    fun `an open buffer is replaced in the editor, not behind its back`() {
        val root = project("main.py" to "иголка\n")
        val workspace = Workspace(ProjectTree(root), Breaker)
        val open = workspace.open(File(root, "main.py"))

        val (_, inBuffers) = replaceEverywhere(workspace, root, "иголка", "гвоздь")

        assertTrue(inBuffers.single().inBuffer, "правка обязана дойти до буфера")
        assertEquals("гвоздь\n", open.state.text.substring(0, open.state.text.length))
        assertEquals("гвоздь\n", File(root, "main.py").readText(), "и уйти на диск")
        assertFalse(open.isModified, "буфер записан, звёздочке взяться неоткуда")
    }

    @Test
    fun `unsaved edits are not overwritten by the replacement`() {
        // Так это и ломается в редакторах, которые правят диск: человек набрал
        // текст, не сохранил, запустил замену — и его правки исчезли.
        val root = project("main.py" to "иголка\n")
        val workspace = Workspace(ProjectTree(root), Breaker)
        val open = workspace.open(File(root, "main.py"))
        open.state.type("добавленное и не сохранённое\n")

        replaceEverywhere(workspace, root, "иголка", "гвоздь")

        val text = open.state.text.let { it.substring(0, it.length) }
        assertEquals("добавленное и не сохранённое\nгвоздь\n", text)
        assertEquals(text, File(root, "main.py").readText())
    }

    @Test
    fun `a buffer opened by another spelling of the same path is still found`() {
        // `File` сравнивается по строке пути: `./main.py` и `main.py` — разные
        // файлы. Буфер, открытый вторым написанием, должен всё равно опознаться
        // как файл проекта — иначе замена пройдёт по диску, а редактор останется
        // показывать прежний текст, и следующее сохранение вернёт его обратно.
        val root = project("main.py" to "иголка\n")
        val workspace = Workspace(ProjectTree(root), Breaker)
        val open = workspace.open(File(root, "./main.py"))
        open.state.type("не сохранено\n")

        replaceEverywhere(workspace, root, "иголка", "гвоздь")

        assertEquals("не сохранено\nгвоздь\n", open.state.text.let { it.substring(0, it.length) })
        assertEquals("не сохранено\nгвоздь\n", File(root, "main.py").readText())
    }

    @Test
    fun `a replacement in an open buffer can be undone`() {
        val root = project("main.py" to "иголка\n")
        val workspace = Workspace(ProjectTree(root), Breaker)
        val open = workspace.open(File(root, "main.py"))

        replaceEverywhere(workspace, root, "иголка", "гвоздь")
        open.state.undo()

        assertEquals("иголка\n", open.state.text.let { it.substring(0, it.length) })
    }

    @Test
    fun `a buffer outside the project is left alone`() {
        // Открыть можно что угодно, а «заменить по проекту» значит по проекту.
        val root = project("main.py" to "иголка\n")
        val outside = Files.createTempDirectory("eide-outside").toFile().apply { deleteOnExit() }
        val stranger = File(outside, "stranger.py").apply { writeText("иголка\n") }

        val workspace = Workspace(ProjectTree(root), Breaker)
        val open = workspace.open(stranger)

        replaceEverywhere(workspace, root, "иголка", "гвоздь")

        assertEquals("иголка\n", open.state.text.let { it.substring(0, it.length) })
        assertEquals("иголка\n", stranger.readText())
    }

    @Test
    fun `a buffer larger than the walk limit is left alone`() {
        // Обход не заходит в крупные файлы, значит и замена не должна: человек
        // не видел этих совпадений в списке.
        val root = project("big.py" to "иголка\n")
        val workspace = Workspace(ProjectTree(root), Breaker)
        val open = workspace.open(File(root, "big.py"))

        workspace.saveModified()
        val query = SearchQuery("иголка")
        ProjectReplace(ProjectTree(root)).replace(query, "гвоздь", maxFileBytes = 4)
        val replayed = replayInBuffers(workspace, query, "гвоздь", maxFileBytes = 4)

        assertTrue(replayed.isEmpty())
        assertEquals("иголка\n", open.state.text.let { it.substring(0, it.length) })
    }

    @Test
    fun `a read-only buffer is left alone`() {
        // Файл больше порога открывается только на чтение, но текст в буфере у
        // него настоящий — значит замена в него полезет, если её не остановить.
        // На диск она при этом не попадёт (`Workspace.save` отказывает), зато
        // редактор показал бы правку в файле, который править нельзя.
        val root = project("big.py" to "иголка\n")
        val workspace = Workspace(ProjectTree(root), Breaker, maxEditableBytes = 4)
        val open = workspace.open(File(root, "big.py"))
        assertTrue(open.isReadOnly, "иначе тест проверяет не то, что думает")

        replayInBuffers(workspace, SearchQuery("иголка"), "гвоздь")

        assertEquals("иголка\n", open.state.text.let { it.substring(0, it.length) })
    }

    @Test
    fun `binary files are left alone`() {
        val root = project("main.py" to "иголка\n")
        val blob = File(root, "blob.bin").apply { writeBytes(byteArrayOf(0, 105, 0)) }

        val result = replace(root, "иголка", "гвоздь")

        assertEquals(1, result.filesSkipped)
        assertEquals(1, result.changed.size)
        assertTrue(blob.readBytes().contentEquals(byteArrayOf(0, 105, 0)))
    }

    @Test
    fun `an empty query changes nothing`() {
        val root = project("main.py" to "иголка\n")

        val result = replace(root, "", "гвоздь")

        assertEquals(0, result.replaced)
        assertEquals("иголка\n", File(root, "main.py").readText())
    }

    @Test
    fun `cancellation stops the replacement and says so`() {
        val root = project("first.py" to "иголка\n", "nested/second.py" to "иголка\n")

        val result = ProjectReplace(ProjectTree(root))
            .replace(SearchQuery("иголка"), "гвоздь", isCancelled = { true })

        assertEquals(SearchOutcome.Cancelled, result.outcome)
        assertEquals(0, result.replaced)
        assertEquals("иголка\n", File(root, "first.py").readText())
    }
}
