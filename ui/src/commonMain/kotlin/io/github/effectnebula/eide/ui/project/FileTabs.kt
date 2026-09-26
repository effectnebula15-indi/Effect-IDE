package io.github.effectnebula.eide.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.project.OpenFile
import io.github.effectnebula.eide.ui.editor.observeEditor
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Вкладки открытых файлов.
 *
 * Звёздочка у имени означает несохранённые правки. Она считается по глубине
 * истории отката, а не по числу нажатий: вернувшийся к исходному тексту файл
 * не изменён, и врать об этом не надо (`docs/modules/core-project.md`).
 *
 * Крестик отделён от имени намеренно: на телефоне промах по крестику вместо
 * имени закрывает файл, который человек хотел открыть.
 */
@Composable
fun FileTabs(
    files: List<OpenFile>,
    active: OpenFile?,
    onSelect: (OpenFile) -> Unit,
    onClose: (OpenFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return

    // Звёздочка обязана появляться с первой же правкой, а не когда экран
    // пересоберётся по другой причине. Правится только активный файл, поэтому
    // подписки на него достаточно.
    if (active != null) observeEditor(active.state)

    Row(
        modifier
            .fillMaxWidth()
            .background(Eide.colors.border)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (file in files) {
            val isActive = file === active
            Row(
                Modifier
                    .background(if (isActive) Eide.colors.background else Eide.colors.panel)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    text = file.name + if (file.isModified) " *" else "",
                    color = if (isActive) Eide.colors.text else Eide.colors.textDim,
                    modifier = Modifier.pointerInput(file) { detectTapGestures { onSelect(file) } },
                )
                Label(
                    text = "  ×",
                    color = Eide.colors.textDim,
                    modifier = Modifier
                        .pointerInput(file) { detectTapGestures { onClose(file) } }
                        // Запас вокруг крестика: без него по нему не попасть пальцем.
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = 13.sp, fontFamily = Eide.editorFont),
    )
}
