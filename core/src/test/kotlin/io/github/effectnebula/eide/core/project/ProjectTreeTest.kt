package io.github.effectnebula.eide.core.project

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Дерево проекта.
 *
 * Половина проверок здесь — про то, чего дерево делать НЕ должно: не
 * разворачивать ссылки, не падать на недоступной папке, не показывать мусор
 * инструментов. Ошибки этого рода выглядят как «IDE зависла» или «где мой
 * файл», и причину ищут где угодно, только не в порядке обхода.
 */
class ProjectTreeTest {

    private fun sandbox(): File = Files.createTempDirectory("eide-tree").toFile().apply {
        deleteOnExit()
    }

    private fun File.dir(name: String): File = File(this, name).apply { mkdirs() }
    private fun File.touch(name: String): File = File(this, name).apply { writeText("") }

    private fun names(entries: List<ProjectEntry>) = entries.map { it.name }

    // --- порядок ---------------------------------------------------------------

    @Test
    fun `folders come before files`() {
        val root = sandbox()
        root.touch("aaa.py")
        root.dir("zzz")

        assertEquals(listOf("zzz", "aaa.py"), names(ProjectTree(root).children(root)))
    }

    @Test
    fun `names are ordered ignoring case`() {
        val root = sandbox()
        listOf("Beta.py", "alpha.py", "Gamma.py").forEach { root.touch(it) }

        assertEquals(listOf("alpha.py", "Beta.py", "Gamma.py"), names(ProjectTree(root).children(root)))
    }

    @Test
    fun `names differing only in case have a stable order`() {
        // Без вторичного сравнения порядок таких имён зависит от реализации
        // сортировки, и дерево «дрожит» между обновлениями.
        val root = sandbox()
        root.touch("File.py")
        root.touch("file.py")

        val first = names(ProjectTree(root).children(root))
        val second = names(ProjectTree(root).children(root))

        assertEquals(first, second)
        assertEquals(2, first.size, "файлы, различающиеся регистром, слились")
    }

    // --- что показывается ------------------------------------------------------

    @Test
    fun `tool clutter is hidden by default`() {
        val root = sandbox()
        root.dir("__pycache__")
        root.dir(".git")
        root.dir("node_modules")
        root.touch(".env")
        root.touch("main.py")

        assertEquals(listOf("main.py"), names(ProjectTree(root).children(root)))
    }

    @Test
    fun `everything filter shows what is hidden`() {
        val root = sandbox()
        root.dir(".git")
        root.touch("main.py")

        val entries = ProjectTree(root, ProjectTree.EntryFilter.Everything).children(root)

        assertEquals(listOf(".git", "main.py"), names(entries))
    }

    @Test
    fun `files and folders get different kinds`() {
        val root = sandbox()
        root.dir("package")
        root.touch("main.py")

        val entries = ProjectTree(root).children(root)

        assertTrue(entries[0] is ProjectFolder)
        assertTrue(entries[1] is ProjectSource)
        assertEquals("py", (entries[1] as ProjectSource).extension)
    }

    @Test
    fun `extension is lowercased and empty when absent`() {
        val root = sandbox()
        root.touch("SHOUT.PY")
        root.touch("Makefile")

        val entries = ProjectTree(root).children(root).filterIsInstance<ProjectSource>()
            .associateBy { it.name }

        assertEquals("py", entries.getValue("SHOUT.PY").extension)
        assertEquals("", entries.getValue("Makefile").extension)
    }

    // --- чего делать нельзя ----------------------------------------------------

    @Test
    fun `directory symlinks are not expanded`() {
        // Ссылка на родителя — бесконечное дерево. Обнаруживается это не сразу,
        // а когда кто-то раскроет десятый уровень.
        val root = sandbox()
        val inner = root.dir("inner")
        runCatching {
            File(inner, "loop").toPath().createSymbolicLinkPointingTo(root.toPath())
        }.onFailure { return }  // на файловой системе без ссылок проверять нечего

        assertEquals(emptyList(), names(ProjectTree(root).children(inner)))
    }

    @Test
    fun `a file symlink stays visible`() {
        val root = sandbox()
        val target = root.touch("real.py")
        runCatching {
            File(root, "link.py").toPath().createSymbolicLinkPointingTo(target.toPath())
        }.onFailure { return }

        assertEquals(listOf("link.py", "real.py"), names(ProjectTree(root).children(root)))
    }

    @Test
    fun `listing something that is not a folder gives nothing`() {
        val root = sandbox()
        val file = root.touch("main.py")

        assertEquals(emptyList(), ProjectTree(root).children(file))
        assertEquals(emptyList(), ProjectTree(root).children(File(root, "нет такой папки")))
    }

    // --- путь от корня ---------------------------------------------------------

    @Test
    fun `relative path is counted from the project root`() {
        val root = sandbox()
        val nested = root.dir("pkg").dir("sub")
        val file = File(nested, "main.py").apply { writeText("") }

        assertEquals(
            listOf("pkg", "sub", "main.py").joinToString(File.separator),
            ProjectTree(root).relativePath(file),
        )
    }

    @Test
    fun `the root itself has an empty relative path`() {
        val root = sandbox()

        assertEquals("", ProjectTree(root).relativePath(root))
    }

    @Test
    fun `a file outside the project has no relative path`() {
        val root = sandbox()
        val outside = sandbox().touch("stranger.py")

        assertNull(ProjectTree(root).relativePath(outside))
    }

    @Test
    fun `a sibling folder with the same prefix is not counted as inside`() {
        // "proj" и "project" начинаются одинаково: сравнение по префиксу без
        // разделителя объявило бы соседа частью проекта.
        val parent = sandbox()
        val root = parent.dir("proj")
        val sibling = parent.dir("project")
        val file = File(sibling, "main.py").apply { writeText("") }

        assertNull(ProjectTree(root).relativePath(file))
    }
}
