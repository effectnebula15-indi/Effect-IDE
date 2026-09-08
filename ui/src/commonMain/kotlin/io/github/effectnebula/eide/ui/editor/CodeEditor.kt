package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.editor.SearchSession
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.theme.Eide
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Цвета редактора. Отдельно от темы, чтобы отрисовку можно было проверять в изоляции. */
data class EditorColors(
    val background: Color,
    val text: Color,
    val gutterBackground: Color,
    val gutterText: Color,
    val currentLineGutterText: Color,
    val selection: Color,
    val caret: Color,
    /** Подсветка совпадений поиска. Под выделением, поэтому заметно бледнее. */
    val searchMatch: Color,
)

/**
 * Редактор кода: гаттер с номерами строк, текст, выделение, курсоры.
 *
 * Рисуется целиком сам, без `BasicTextField`: тот держит текст как одну строку и
 * не умеет виртуализировать — на файле в тысячи строк это неприемлемо (ADR-001).
 *
 * Ввод идёт двумя путями: аппаратная клавиатура через `editorKeyInput`, экранная
 * через `imeInput` — на Android это разные механизмы, и общего у них только
 * результат.
 */
@Composable
fun CodeEditor(
    state: EditorState,
    colors: EditorColors,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    search: SearchSession? = null,
) {
    val measurer = rememberTextMeasurer()
    val font = Eide.editorFont
    val style = remember(fontSizeSp, font) {
        TextStyle(fontSize = fontSizeSp.sp, fontFamily = font)
    }
    val cache = remember { LineLayoutCache<TextLayoutResult>() }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val metrics = remember(style, measurer) {
        val sample = measurer.measure("0", style)
        LineMetrics(
            height = sample.size.height.toFloat(),
            digitWidth = sample.size.width.toFloat(),
        )
    }

    var scrollPx by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var caretVisible by remember { mutableStateOf(true) }

    val revision = rememberEditorRevision(state)

    // Границы прокрутки и ширина гаттера зависят от числа строк, а оно меняется
    // при правке. Считаются на месте использования, а не в теле composable:
    // пересборки на каждое нажатие мы как раз избегаем.
    val scrollState = rememberScrollableState { delta ->
        val previous = scrollPx
        scrollPx = (scrollPx - delta).coerceIn(0f, maxScrollPx(state, metrics, viewportHeight))
        previous - scrollPx
    }

    // Реакция на любое изменение: догнать курсор прокруткой и перезапустить
    // мигание. Мигание сбрасывается потому, что иначе курсор пропадает ровно в
    // тот момент, когда на него смотрят.
    //
    // Прокрутка живёт здесь, а не в обработчиках ввода, намеренно: источников
    // движения курсора уже четыре — клавиатура, IME, дополнительный ряд, undo —
    // и в каждом про прокрутку пришлось бы помнить отдельно. Ручную прокрутку
    // это не перебивает: без изменения состояния сюда ничего не приходит, а
    // видимый курсор scrollToCaret оставляет на месте.
    //
    // Перезапуск идёт через snapshotFlow, а не через ключи LaunchedEffect:
    // ключи пересчитываются только при пересборке, а её здесь намеренно нет.
    LaunchedEffect(revision) {
        snapshotFlow { revision.longValue }.collectLatest {
            scrollPx = scrollToCaret(state, metrics, scrollPx, viewportHeight)
            caretVisible = true
            while (true) {
                delay(CARET_BLINK_MS)
                caretVisible = !caretVisible
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(modifier.background(colors.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .scrollable(scrollState, Orientation.Vertical)
                .imeInput(state)
                .focusRequester(focusRequester)
                .focusable()
                .editorKeyInput(state)
                .pointerInput(metrics) {
                    detectTapGestures { position ->
                        val text = state.text
                        val gutter = gutterWidthPx(text.lineCount, metrics)
                        state.setCarets(
                            CaretSet.single(offsetAt(text, metrics, gutter, scrollPx, position))
                        )
                        runCatching { focusRequester.requestFocus() }
                        // Тап по тексту — просьба печатать. Клавиатуру, закрытую
                        // кнопкой «назад», иначе не вернуть: фокус уже здесь,
                        // и сессия ввода не перезапустится сама.
                        keyboard?.show()
                    }
                }
        ) {
            viewportHeight = size.height.roundToInt()
            // Чтение ревизии в фазе отрисовки — это подписка: правка перерисует
            // холст, не трогая пересборку.
            revision.longValue
            drawEditor(
                state = state,
                measurer = measurer,
                style = style,
                cache = cache,
                colors = colors,
                metrics = metrics,
                gutterWidth = gutterWidthPx(state.text.lineCount, metrics),
                scrollPx = scrollPx,
                caretVisible = caretVisible,
                search = search,
            )
        }
    }
}

internal data class LineMetrics(val height: Float, val digitWidth: Float)

private const val CARET_BLINK_MS = 530L
private const val CARET_WIDTH_PX = 2f
private const val GUTTER_PADDING_DIGITS = 2

/**
 * Разделитель ключей кэша: номера строк и текст строк не должны сталкиваться.
 *
 * Записан escape-последовательностью, а не самим байтом: файл с настоящим `\u0000`
 * внутри git считает бинарным, и diff по нему перестаёт читаться.
 */
private const val GUTTER_KEY_PREFIX = "\u0000gutter\u0000"

private fun gutterWidthPx(lineCount: Int, metrics: LineMetrics): Float {
    val digits = lineCount.toString().length + GUTTER_PADDING_DIGITS
    return digits * metrics.digitWidth
}

/**
 * Докуда можно прокрутить.
 *
 * Половина экрана пустоты снизу оставлена намеренно: последняя строка файла
 * должна доводиться до середины экрана, иначе на ней неудобно работать —
 * особенно на телефоне, где низ экрана занят клавиатурой.
 */
private fun maxScrollPx(state: EditorState, metrics: LineMetrics, viewportHeight: Int): Float =
    max(0f, state.text.lineCount * metrics.height - viewportHeight / 2f)

private fun DrawScope.drawEditor(
    state: EditorState,
    measurer: TextMeasurer,
    style: TextStyle,
    cache: LineLayoutCache<TextLayoutResult>,
    colors: EditorColors,
    metrics: LineMetrics,
    gutterWidth: Float,
    scrollPx: Float,
    caretVisible: Boolean,
    search: SearchSession?,
) {
    val text = state.text
    if (metrics.height <= 0f) return

    val first = (scrollPx / metrics.height).toInt().coerceAtLeast(0)
    val visible = (size.height / metrics.height).roundToInt() + 2
    val last = min(first + visible, text.lineCount)

    // Совпадения ищутся только в видимых строках: искать по всему документу
    // ради подсветки экрана — это проход по мегабайтам на каждый кадр.
    val matches = if (search == null || last <= first) {
        emptyList()
    } else {
        search.matchesIn(text.lineStart(first), text.lineEnd(last - 1))
    }

    val textWidth = (size.width - gutterWidth).coerceAtLeast(1f)
    val constraints = Constraints(maxWidth = textWidth.roundToInt())

    drawRect(colors.gutterBackground, size = Size(gutterWidth, size.height))

    val caretLines = state.carets.carets.map { text.lineOf(it.head) }.toSet()

    translate(top = -scrollPx) {
        for (line in first until last) {
            val top = line * metrics.height
            val lineStart = text.lineStart(line)
            val lineEnd = text.lineEnd(line)
            val lineText = text.substring(lineStart, lineEnd)
            val layout = cache.get(lineText) { measurer.measure(it, style, constraints = constraints) }

            for (match in matches) {
                drawRange(
                    colors.searchMatch, metrics, gutterWidth, layout,
                    lineStart, lineEnd, top, match.start, match.end,
                )
            }

            drawSelection(state, colors, metrics, gutterWidth, layout, lineStart, lineEnd, top)

            // Номер прижат вправо: столбец цифр не пляшет при переходе через
            // десяток, сотню и тысячу.
            val number = (line + 1).toString()
            val numberLayout = cache.get(GUTTER_KEY_PREFIX + number) { measurer.measure(number, style) }
            drawText(
                numberLayout,
                color = if (line in caretLines) colors.currentLineGutterText else colors.gutterText,
                topLeft = Offset(gutterWidth - numberLayout.size.width - metrics.digitWidth, top),
            )

            drawText(layout, color = colors.text, topLeft = Offset(gutterWidth, top))

            if (caretVisible) {
                drawCarets(state, colors, metrics, gutterWidth, layout, lineStart, lineEnd, top)
            }
        }
    }
}

private fun DrawScope.drawSelection(
    state: EditorState,
    colors: EditorColors,
    metrics: LineMetrics,
    gutterWidth: Float,
    layout: TextLayoutResult,
    lineStart: Int,
    lineEnd: Int,
    top: Float,
) {
    for (caret in state.carets.carets) {
        if (caret.isEmpty) continue
        drawRange(
            colors.selection, metrics, gutterWidth, layout,
            lineStart, lineEnd, top, caret.start, caret.end,
        )
    }
}

/**
 * Закрашивает пересечение диапазона `[from, to)` с этой строкой.
 *
 * Общий код для выделения и подсветки поиска: правило «диапазон, захвативший
 * перенос, тянется до края» одно на двоих, и разъезжаться ему незачем.
 */
private fun DrawScope.drawRange(
    color: Color,
    metrics: LineMetrics,
    gutterWidth: Float,
    layout: TextLayoutResult,
    lineStart: Int,
    lineEnd: Int,
    top: Float,
    from: Int,
    to: Int,
) {
    if (to < lineStart || from > lineEnd) return

    val length = layout.layoutInput.text.length
    val start = (from.coerceAtLeast(lineStart) - lineStart).coerceIn(0, length)
    val end = (to.coerceAtMost(lineEnd) - lineStart).coerceIn(0, length)

    val left = gutterWidth + layout.getHorizontalPosition(start, usePrimaryDirection = true)
    val right = gutterWidth + layout.getHorizontalPosition(end, usePrimaryDirection = true)
    // Диапазон, захвативший перенос строки, тянем до края: иначе не видно, что
    // выбрана строка целиком.
    val extended = if (to > lineEnd) size.width else right

    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size((extended - left).coerceAtLeast(1f), metrics.height),
    )
}

private fun DrawScope.drawCarets(
    state: EditorState,
    colors: EditorColors,
    metrics: LineMetrics,
    gutterWidth: Float,
    layout: TextLayoutResult,
    lineStart: Int,
    lineEnd: Int,
    top: Float,
) {
    val length = layout.layoutInput.text.length
    for (caret in state.carets.carets) {
        if (caret.head < lineStart || caret.head > lineEnd) continue
        val within = (caret.head - lineStart).coerceIn(0, length)
        val x = gutterWidth + layout.getHorizontalPosition(within, usePrimaryDirection = true)
        drawRect(
            color = colors.caret,
            topLeft = Offset(x, top),
            size = Size(CARET_WIDTH_PX, metrics.height),
        )
    }
}

/** Куда поставить курсор по касанию. */
internal fun offsetAt(
    text: Rope,
    metrics: LineMetrics,
    gutterWidth: Float,
    scrollPx: Float,
    position: Offset,
): Int {
    val line = ((position.y + scrollPx) / metrics.height).toInt().coerceIn(0, text.lineCount - 1)
    val lineStart = text.lineStart(line)
    val lineEnd = text.lineEnd(line)

    // Колонка считается по ширине цифры: шрифт моноширинный, все знаки одинаковы.
    val column = ((position.x - gutterWidth) / metrics.digitWidth).roundToInt().coerceAtLeast(0)
    return (lineStart + column).coerceIn(lineStart, lineEnd)
}

/** Прокручивает так, чтобы курсор остался виден. */
internal fun scrollToCaret(
    state: EditorState,
    metrics: LineMetrics,
    scrollPx: Float,
    viewportHeight: Int,
): Float {
    if (viewportHeight <= 0 || metrics.height <= 0f) return scrollPx
    val line = state.text.lineOf(state.carets.primary.head)
    val top = line * metrics.height
    val bottom = top + metrics.height

    return when {
        top < scrollPx -> top
        bottom > scrollPx + viewportHeight -> bottom - viewportHeight
        else -> scrollPx
    }
}
