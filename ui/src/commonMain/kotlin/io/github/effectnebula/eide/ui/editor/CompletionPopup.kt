package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Список вариантов у курсора.
 *
 * Где он стоит, решает редактор — только у него есть геометрия курсора. Здесь
 * только то, что внутри рамки.
 *
 * Высота ограничена: список на полэкрана закрывает код, ради которого его
 * открыли. Остальное прокручивается, и выбранная строка держится в виду.
 */
@Composable
internal fun CompletionPopup(
    controller: CompletionController,
    onAccept: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(controller.selected, controller.shown.size) {
        if (controller.shown.isNotEmpty()) listState.scrollToItem(controller.selected)
    }

    LazyColumn(
        modifier
            .testTag(COMPLETION_TAG)
            // Ширина постоянная: у ленивого списка нет измерения по содержимому,
            // а строка выбора должна быть во всю ширину, не только под текстом —
            // первая редакция подсвечивала одно слово, и это было видно на снимке.
            .width(POPUP_WIDTH)
            .heightIn(max = 240.dp)
            .background(Eide.colors.panel)
            .border(1.dp, Eide.colors.border),
        state = listState,
    ) {
        itemsIndexed(controller.shown) { index, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (index == controller.selected) Eide.colors.selection else Eide.colors.panel)
                    .clickable { onAccept(index) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    text = item.label,
                    style = TextStyle(color = Eide.colors.text, fontSize = 13.sp, fontFamily = Eide.editorFont),
                    maxLines = 1,
                )
                item.detail?.let {
                    BasicText(
                        text = it,
                        style = TextStyle(color = Eide.colors.textDim, fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Метка для тестов: список то есть, то нет, и проверять это надо по узлу. */
internal const val COMPLETION_TAG = "completion"

/** Ширина списка: имя с сигнатурой средней длины помещается, код не закрывается. */
private val POPUP_WIDTH = 320.dp
