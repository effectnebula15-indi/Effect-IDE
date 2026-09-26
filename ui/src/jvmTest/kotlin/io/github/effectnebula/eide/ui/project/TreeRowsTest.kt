package io.github.effectnebula.eide.ui.project

import io.github.effectnebula.eide.core.project.ProjectEntry
import io.github.effectnebula.eide.core.project.ProjectFolder
import io.github.effectnebula.eide.core.project.ProjectSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разворачивание дерева в список строк.
 *
 * Диск здесь не нужен: содержимое папок подставляется таблицей. Это и есть
 * причина, по которой обход вынесен из composable — иначе проверить порядок и
 * глубину можно было бы только глазами.
 */
class TreeRowsTest {

    private val root = ProjectFolder(File("/p"))

    private fun folder(path: String) = ProjectFolder(File(path))
    private fun source(path: String) = ProjectSource(File(path))

    private val layout: Map<String, List<ProjectEntry>> = mapOf(
        "/p" to listOf(folder("/p/pkg"), source("/p/main.py")),
        "/p/pkg" to listOf(folder("/p/pkg/deep"), source("/p/pkg/util.py")),
        "/p/pkg/deep" to listOf(source("/p/pkg/deep/inner.py")),
    )

    private fun children(folder: ProjectFolder): List<ProjectEntry> =
        layout[folder.file.path].orEmpty()

    private fun rows(vararg expanded: String) =
        flattenTree(root, expanded.toSet(), ::children)

    private fun names(rows: List<TreeRow>) = rows.map { it.entry.name }

    @Test
    fun `a collapsed root shows only itself`() {
        assertEquals(1, rows().size)
        assertEquals(0, rows().single().depth)
    }

    @Test
    fun `expanding shows children in order, folders first`() {
        val rows = rows("/p")

        assertEquals(listOf("p", "pkg", "main.py"), names(rows))
        assertEquals(listOf(0, 1, 1), rows.map { it.depth })
    }

    @Test
    fun `nested expansion keeps the order of the outer level`() {
        // Дети раскрытой папки обязаны встать между ней и её соседом, а не в
        // конец списка: иначе дерево перестаёт быть деревом.
        val rows = rows("/p", "/p/pkg")

        assertEquals(listOf("p", "pkg", "deep", "util.py", "main.py"), names(rows))
        assertEquals(listOf(0, 1, 2, 2, 1), rows.map { it.depth })
    }

    @Test
    fun `deep expansion counts depth from the root`() {
        val rows = rows("/p", "/p/pkg", "/p/pkg/deep")

        assertEquals(listOf("p", "pkg", "deep", "inner.py", "util.py", "main.py"), names(rows))
        assertEquals(listOf(0, 1, 2, 3, 2, 1), rows.map { it.depth })
    }

    @Test
    fun `expanding a folder that is not shown changes nothing`() {
        // Раскрытая вложенная папка при свёрнутой внешней не должна протекать
        // наружу — состояние раскрытия переживает сворачивание родителя.
        assertEquals(listOf("p"), names(rows("/p/pkg")))
    }

    @Test
    fun `an empty folder is still a row`() {
        val rows = flattenTree(root, setOf("/p"), { emptyList() })

        assertEquals(listOf("p"), names(rows))
    }

    @Test
    fun `children are read only for expanded folders`() {
        // Чтение диска на свёрнутой папке — то, ради чего дерево вообще
        // ленивое: в проекте с node_modules это секунды.
        val asked = mutableListOf<String>()

        flattenTree(root, setOf("/p"), { folder ->
            asked += folder.file.path
            children(folder)
        })

        assertEquals(listOf("/p"), asked, "содержимое читалось у свёрнутых папок")
    }

    @Test
    fun `folders are marked as folders`() {
        val rows = rows("/p")

        assertTrue(rows[1].isFolder)
        assertTrue(!rows[2].isFolder)
    }
}
