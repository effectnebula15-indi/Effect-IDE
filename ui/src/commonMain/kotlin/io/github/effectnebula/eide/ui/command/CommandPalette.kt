package io.github.effectnebula.eide.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.command.Command
import io.github.effectnebula.eide.core.command.CommandMatch
import io.github.effectnebula.eide.core.command.rankCommands
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Палитра команд.
 *
 * На телефоне это единственный способ дотянуться до действия, которому не
 * хватило кнопки: панель там одна, места под панель инструментов нет, а меню
 * в этом интерфейсе не заведено. На десктопе — способ не искать кнопку глазами.
 *
 * Список ранжируется нечётким поиском (`core/command/FuzzyRank.kt`), совпавшие
 * буквы подсвечиваются: без подсветки непонятно, почему команда вообще в списке,
 * и нечёткий поиск выглядит как случайный.
 *
 * Клавиши: стрелки водят по списку, Enter выполняет выбранное, Esc закрывает.
 * Перехват стоит до поля ввода (`onPreviewKeyEvent`), иначе стрелки уедут в
 * текст запроса и будут двигать курсор вместо выбора.
 */
@Composable
fun CommandPalette(
    commands: List<Command>,
    onRun: (Command) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }

    val matches = remember(commands, query) { rankCommands(commands, query) }

    // Выбор подрезается по длине списка, а не сбрасывается в ноль: человек,
    // дописавший букву, обычно уточняет прежнее намерение, а не начинает заново.
    val current = selected.coerceIn(0, (matches.size - 1).coerceAtLeast(0))

    val listState = rememberLazyListState()
    LaunchedEffect(current, matches.size) {
        if (matches.isNotEmpty()) listState.animateScrollToItem(current)
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun move(delta: Int) {
        if (matches.isEmpty()) return
        // По кругу: список короткий, и упираться в его край незачем.
        selected = (current + delta + matches.size) % matches.size
    }

    fun run() {
        val match = matches.getOrNull(current) ?: return
        onRun(match.command)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Eide.colors.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { move(+1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.Enter, Key.NumPadEnter -> { run(); true }
                    Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        BasicTextField(
            value = query,
            onValueChange = {
                query = it
                selected = 0
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .background(Eide.colors.panel)
                .padding(10.dp)
                .focusRequester(focus),
            textStyle = TextStyle(color = Eide.colors.text, fontSize = 15.sp),
            cursorBrush = SolidColor(Eide.colors.caret),
        )

        if (matches.isEmpty()) {
            BasicText(
                text = "нет такой команды",
                modifier = Modifier.fillMaxWidth().background(Eide.colors.border)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = TextStyle(color = Eide.colors.textDim, fontSize = 12.sp),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(matches) { index, match ->
                CommandRow(match, index == current) {
                    selected = index
                    onRun(match.command)
                }
            }
        }
    }
}

@Composable
private fun CommandRow(match: CommandMatch, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Eide.colors.selection else Eide.colors.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = highlight(match, Eide.colors.accent),
            style = TextStyle(color = Eide.colors.text, fontSize = 14.sp),
        )
        match.command.hint?.let {
            BasicText(it, style = TextStyle(color = Eide.colors.textDim, fontSize = 12.sp))
        }
    }
}

/**
 * Совпавшие буквы — жирным и цветом акцента.
 *
 * Без этого нечёткий поиск выглядит случайным: непонятно, почему «Зафиксировать
 * изменения» вообще нашлась по запросу «заф» и почему она выше соседа.
 */
private fun highlight(match: CommandMatch, color: Color): AnnotatedString {
    val title = match.command.title
    return AnnotatedString.Builder(title).apply {
        for (index in match.positions) {
            addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), index, index + 1)
        }
    }.toAnnotatedString()
}
