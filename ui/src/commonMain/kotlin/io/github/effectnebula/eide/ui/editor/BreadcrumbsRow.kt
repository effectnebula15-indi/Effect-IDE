package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.Crumb
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Путь до строки с курсором: `class Игра › def шаг(self, время)`.
 *
 * Нажатие на шаг ведёт к его заголовку. Это главное, ради чего строка вообще
 * кликабельна: увидеть, где ты, и вернуться к началу блока — одно движение.
 *
 * Прокручивается вбок, а не переносится: строка постоянной высоты не дёргает
 * редактор вверх-вниз при движении курсора. На телефоне глубокий путь иначе
 * съедал бы по три строки экрана из тридцати.
 *
 * Пустой путь рисует пустую строку той же высоты — по той же причине.
 */
@Composable
private fun BreadcrumbsRow(
    crumbs: List<Crumb>,
    onGoTo: (Crumb) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .testTag(BREADCRUMBS_TAG)
            .background(Eide.colors.panel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                BasicText(
                    text = " › ",
                    style = TextStyle(color = Eide.colors.textDim, fontSize = 11.sp),
                )
            }
            BasicText(
                text = crumb.title,
                modifier = Modifier.clickable { onGoTo(crumb) },
                style = TextStyle(color = Eide.colors.textDim, fontSize = 11.sp),
            )
        }
    }
}

/**
 * Строка пути над редактором.
 *
 * [outline] — чем считать путь. Функцией, а не зашитым `PythonOutline`, потому
 * что расчёт по отступам верен для Python и неверен для языка со скобками:
 * `:ui` не должен решать за язык. Точка сборки подставляет расчёт для `.py` и
 * ничего для остальных; `null` убирает строку совсем.
 *
 * Пересчитывается на каждое движение курсора — за этим строка и нужна. Цена
 * движения — подъём по отступам вверх до нулевого (разбор в `core-breadcrumbs.md`),
 * то есть обычно десяток строк.
 */
@Composable
fun Breadcrumbs(
    state: EditorState,
    outline: ((Rope, Int) -> List<Crumb>)?,
    modifier: Modifier = Modifier,
) {
    val revision = rememberEditorRevision(state)

    // Ключ — признак «считаем ли», а не сама функция: ссылка на метод с места
    // вызова пересоздаётся, и путь считался бы заново на каждой пересборке, а не
    // только на движении курсора. Здесь это цена, а не поломка — в отличие от
    // пометок гаттера, где такой ключ выбрасывал уже посчитанное (`ui-editor.md`).
    val enabled = outline != null
    val crumbs = remember(state, enabled, revision.longValue) {
        if (outline == null) {
            emptyList()
        } else {
            outline(state.text, state.text.lineOf(state.carets.primary.head))
        }
    }

    if (!enabled) return

    BreadcrumbsRow(
        crumbs = crumbs,
        // Нажатие ведёт к заголовку блока. Курсор ставится на его начало, а
        // прокрутка к курсору — работа самого редактора.
        onGoTo = { crumb -> state.setCarets(CaretSet.single(state.text.lineStart(crumb.line))) },
        modifier = modifier,
    )
}

/**
 * Метка строки для тестов.
 *
 * Нужна ровно затем, что пустой путь и отсутствие расчёта на экране выглядят
 * одинаково — пусто, — а ведут себя по-разному: в первом случае строка остаётся
 * и держит высоту, во втором её нет совсем. Без метки проверить это нечем.
 */
internal const val BREADCRUMBS_TAG = "breadcrumbs"
