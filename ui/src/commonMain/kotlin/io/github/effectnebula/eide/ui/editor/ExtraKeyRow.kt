package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.editor.MoveTo

/** Кнопка дополнительного ряда: что написано и что делает. */
data class ExtraKey(val label: String, val action: (EditorState) -> Unit)

/**
 * Ряд клавиш над экранной клавиатурой.
 *
 * Без него на телефоне не написать ни строчки кода: на системной клавиатуре нет
 * ни Tab, ни стрелок, а скобки и двоеточие спрятаны на втором-третьем экране.
 *
 * Порядок — по частоте в коде, а не по клавиатурной раскладке: ряд
 * прокручивается, и то, что уехало вправо, стоит нажатий. Набор наверняка
 * придётся править после первого же часа работы на настоящем телефоне — это
 * ровно тот случай, когда угадать из кресла нельзя.
 *
 * Нажатие обрабатывается через `pointerInput`, а не `clickable`: `clickable`
 * забирает фокус, а вместе с фокусом уходит сессия ввода — клавиатура закрылась
 * бы от нажатия на кнопку рядом с ней.
 */
@Composable
fun ExtraKeyRow(
    state: EditorState,
    colors: EditorColors,
    modifier: Modifier = Modifier,
    keys: List<ExtraKey> = DEFAULT_EXTRA_KEYS,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(EXTRA_KEY_ROW_HEIGHT)
            .background(colors.gutterBackground)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in keys) {
            Box(
                Modifier
                    .widthIn(min = EXTRA_KEY_MIN_WIDTH)
                    .height(EXTRA_KEY_ROW_HEIGHT)
                    .background(colors.background)
                    .pointerInput(key, state) {
                        detectTapGestures { key.action(state) }
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = key.label,
                    style = TextStyle(
                        color = colors.text,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

private val EXTRA_KEY_ROW_HEIGHT = 40.dp

/** Палец шириной около 9 мм; меньше 40dp промахи становятся правилом. */
private val EXTRA_KEY_MIN_WIDTH = 40.dp

private fun inserting(label: String, text: String = label): ExtraKey =
    ExtraKey(label) { it.type(text) }

private fun moving(label: String, to: MoveTo): ExtraKey =
    ExtraKey(label) { it.move(to) }

/**
 * Набор по умолчанию.
 *
 * Первыми — то, без чего нельзя вообще: отступ и стрелки. Дальше синтаксис
 * Python и C по убыванию частоты.
 */
val DEFAULT_EXTRA_KEYS: List<ExtraKey> = listOf(
    ExtraKey("⇥") { it.type(INDENT) },
    moving("←", MoveTo.Left),
    moving("↑", MoveTo.Up),
    moving("↓", MoveTo.Down),
    moving("→", MoveTo.Right),
    inserting(":"),
    inserting("("),
    inserting(")"),
    inserting("["),
    inserting("]"),
    inserting("{"),
    inserting("}"),
    inserting("\""),
    inserting("'"),
    inserting("_"),
    inserting("="),
    inserting("<"),
    inserting(">"),
    inserting("+"),
    inserting("-"),
    inserting("*"),
    inserting("/"),
    inserting("#"),
    inserting(","),
    inserting("."),
    inserting(";"),
    inserting("&"),
    inserting("|"),
    inserting("%"),
    inserting("!"),
    inserting("?"),
    inserting("@"),
    inserting("\\"),
    ExtraKey("⌫") { it.deleteBackward() },
    ExtraKey("⎌") { it.undo() },
    ExtraKey("↷") { it.redo() },
)
