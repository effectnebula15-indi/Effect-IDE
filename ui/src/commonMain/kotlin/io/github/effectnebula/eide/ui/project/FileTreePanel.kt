package io.github.effectnebula.eide.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.project.ProjectEntry
import io.github.effectnebula.eide.core.project.ProjectFolder
import io.github.effectnebula.eide.core.project.ProjectTree
import io.github.effectnebula.eide.ui.theme.Eide
import java.io.File

/**
 * Дерево файлов проекта.
 *
 * Содержимое папок читается при раскрытии и запоминается: читать диск на каждый
 * кадр прокрутки нельзя, а перечитывать при каждой пересборке — почти то же
 * самое. Кнопка обновления есть и видна намеренно: слежения за диском нет
 * (см. `docs/modules/core-project.md`), и делать вид, что дерево живое, хуже,
 * чем сказать прямо.
 */
@Composable
fun FileTreePanel(
    tree: ProjectTree,
    onOpen: (File) -> Unit,
    modifier: Modifier = Modifier,
    selected: File? = null,
) {
    var expanded by remember(tree) { mutableStateOf(setOf(tree.root.path)) }
    var generation by remember(tree) { mutableStateOf(0) }

    val cache = remember(tree, generation) { HashMap<String, List<ProjectEntry>>() }
    val rows = remember(tree, expanded, generation) {
        flattenTree(tree.rootEntry(), expanded) { folder ->
            cache.getOrPut(folder.file.path) { tree.children(folder) }
        }
    }

    Box(modifier.fillMaxSize().background(Eide.colors.panel)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(tree) { detectTapGestures { generation++ } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Line("обновить дерево", Eide.colors.accent)
                }
            }

            items(rows, key = { it.entry.file.path }) { row ->
                TreeLine(
                    row = row,
                    expanded = row.entry.file.path in expanded,
                    isSelected = selected?.path == row.entry.file.path,
                    onClick = {
                        if (row.entry is ProjectFolder) {
                            val path = row.entry.file.path
                            expanded = if (path in expanded) expanded - path else expanded + path
                        } else {
                            onOpen(row.entry.file)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TreeLine(
    row: TreeRow,
    expanded: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isSelected) Eide.colors.selection else Eide.colors.panel

    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .pointerInput(row, expanded) { detectTapGestures { onClick() } }
            // Отступ ставится слева от строки, а не внутри неё: иначе подсветка
            // выделенного файла обрывается на отступе и выглядит поломкой.
            .padding(start = INDENT_STEP * row.depth + 8.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val marker = when {
            !row.isFolder -> "  "
            expanded -> "▾ "
            else -> "▸ "
        }
        // Файлы и папки одного цвета: приглушать файлы неправильно — по ним и
        // кликают. Различает их треугольник раскрытия, а не яркость.
        Line(marker + row.entry.name, Eide.colors.text)
    }
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color) {
    BasicText(
        text = text,
        style = TextStyle(color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
    )
}

/** Шаг отступа: заметен, но не съедает ширину на телефоне. */
private val INDENT_STEP = 14.dp
