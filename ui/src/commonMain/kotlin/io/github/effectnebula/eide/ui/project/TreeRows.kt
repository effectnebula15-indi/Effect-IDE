package io.github.effectnebula.eide.ui.project

import io.github.effectnebula.eide.core.project.ProjectEntry
import io.github.effectnebula.eide.core.project.ProjectFolder

/** Строка дерева: что показать и на какой глубине. */
data class TreeRow(val entry: ProjectEntry, val depth: Int) {
    val isFolder: Boolean get() = entry is ProjectFolder
}

/**
 * Разворачивает дерево в плоский список видимых строк.
 *
 * Плоский — потому что рисовать его будет `LazyColumn`, а тот показывает только
 * то, что попало на экран. Вложенные composable-функции на каждую папку дали бы
 * то же самое, но с пересборкой всего поддерева на каждое раскрытие.
 *
 * Обход итеративный, а не рекурсивный: глубина здесь задаётся содержимым диска,
 * а не нами, и переполнение стека от чужой структуры папок — плохой способ
 * узнать об этом.
 *
 * [childrenOf] отдаётся снаружи, чтобы содержимое папок можно было кэшировать:
 * читать диск на каждый кадр прокрутки нельзя.
 */
internal fun flattenTree(
    root: ProjectFolder,
    expanded: Set<String>,
    childrenOf: (ProjectFolder) -> List<ProjectEntry>,
): List<TreeRow> {
    val rows = ArrayList<TreeRow>()

    // Стек невыведенных строк. Дети кладутся в обратном порядке, чтобы со
    // стека они снимались в прямом.
    val pending = ArrayDeque<TreeRow>()
    pending.addLast(TreeRow(root, 0))

    while (pending.isNotEmpty()) {
        val row = pending.removeLast()
        rows += row

        val folder = row.entry as? ProjectFolder ?: continue
        if (folder.file.path !in expanded) continue

        val children = childrenOf(folder)
        for (index in children.indices.reversed()) {
            pending.addLast(TreeRow(children[index], row.depth + 1))
        }
    }

    return rows
}
