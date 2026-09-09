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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.CaretSet
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.editor.SearchSession
import io.github.effectnebula.eide.core.syntax.LineHighlighter
import io.github.effectnebula.eide.core.syntax.TokenKind
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
    /** Цвета подсветки синтаксиса по видам кусков. */
    val syntax: Map<TokenKind, Color> = emptyMap(),
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
    highlighter: LineHighlighter = LineHighlighter.None,
    /**
     * Куда сообщать новый размер шрифта, выбранный двумя пальцами. `null` —
     * жест не подключается: размер задаёт тот, кто рисует редактор.
     */
    onFontSizeChange: ((Float) -> Unit)? = null,
    /**
     * Крючок для замеров (P2). В обычной работе null, и ничего связанного с ним
     * не исполняется. Существует ради того, чтобы стенд мерил этот рендер,
     * а не свой собственный — см. [RenderProbe].
     */
    probe: RenderProbe? = null,
) {
    val measurer = rememberTextMeasurer()
    val font = Eide.editorFont
    // Цвет в базовом стиле, а не в drawText: куски подсветки перекрывают его
    // выборочно, а перекрытие сверху покрасило бы строку целиком.
    val style = remember(fontSizeSp, font, colors.text) {
        TextStyle(fontSize = fontSizeSp.sp, fontFamily = font, color = colors.text)
    }
    // Кэш живёт вместе со стилем, а не вечно: ключ в нём — текст строки, и
    // разметка, снятая при другом размере шрифта, по тому же ключу нашлась бы
    // снова. Смена размера редка, потерять кэш на ней не жалко.
    val cache = remember(style) { LineLayoutCache<TextLayoutResult>() }
    val lineStates = remember(highlighter) { LineStates(highlighter) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val metrics = remember(style, measurer) {
        val sample = measurer.measure("0", style)
        LineMetrics(
            height = sample.size.height.toFloat(),
            digitWidth = sample.size.width.toFloat(),
        )
    }

    // Разметка строки в одном месте на всех потребителей: рисование, попадание
    // тапа и прокрутка за курсором обязаны видеть строку одинаково. Пока это
    // были две арифметики — по разметке при рисовании и по ширине цифры при
    // тапе, — курсор на строке с иероглифом вставал не туда, куда ткнули.
    val lineLayout: (Int) -> TextLayoutResult = { line ->
        val text = state.text
        val lineText = text.substring(text.lineStart(line), text.lineEnd(line))
        // Состояние входит в ключ кэша: строка внутри докстринга и такая же
        // снаружи выглядят одинаково, а красятся по-разному.
        val stateBefore = lineStates.stateBefore(state.document, line)
        val key = if (stateBefore == 0) lineText else "$stateBefore\u0000$lineText"
        cache.get(key) {
            // Без переноса и без ограничения ширины. Перенос сломал бы всю
            // арифметику: высота строки здесь постоянная, и перенесённый хвост
            // рисовался бы поверх следующей строки. Цена — разметка строки
            // целиком, какой бы длинной она ни была; на файле из одной строки
            // в мегабайт это будет заметно.
            measurer.measure(
                highlighted(lineText, stateBefore, highlighter, colors),
                style,
                softWrap = false,
                constraints = Constraints(),
            )
        }
    }

    var scrollPx by remember { mutableFloatStateOf(0f) }
    var scrollXPx by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var caretVisible by remember { mutableStateOf(true) }

    // Ширина самой длинной строки, которую довелось разметить. Настоящий максимум
    // по документу стоил бы прохода по всему файлу на каждый кадр, поэтому граница
    // прокрутки растёт по мере того, как длинные строки попадаются на глаза.
    // Цена: побывав на длинной строке, вправо можно уехать в пустоту и на коротких.
    // Так же ведут себя все редакторы, которые я видел.
    var widestLinePx by remember { mutableFloatStateOf(0f) }

    val revision = rememberEditorRevision(state)

    // Границы прокрутки и ширина гаттера зависят от числа строк, а оно меняется
    // при правке. Считаются на месте использования, а не в теле composable:
    // пересборки на каждое нажатие мы как раз избегаем.
    val scrollState = rememberScrollableState { delta ->
        val previous = scrollPx
        scrollPx = (scrollPx - delta).coerceIn(0f, maxScrollPx(state, metrics, viewportHeight))
        previous - scrollPx
    }

    val horizontalScrollState = rememberScrollableState { delta ->
        val previous = scrollXPx
        val limit = max(0f, gutterWidthPx(state.text.lineCount, metrics) + widestLinePx - viewportWidth)
        scrollXPx = (scrollXPx - delta).coerceIn(0f, limit)
        previous - scrollXPx
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
            scrollXPx = scrollXToCaret(
                caret = caretBounds(state, lineLayout),
                scrollXPx = scrollXPx,
                textWidth = viewportWidth - gutterWidthPx(state.text.lineCount, metrics),
            )
            caretVisible = true
            while (true) {
                delay(CARET_BLINK_MS)
                caretVisible = !caretVisible
            }
        }
    }

    val zoom = if (onFontSizeChange == null) {
        Modifier
    } else {
        Modifier.fontZoom { factor ->
            onFontSizeChange((fontSizeSp * factor).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))
        }
    }

    // Ввод при замерах не подключается совсем. Причина не в стоимости узлов,
    // а в том, что сессия ввода поднимает на телефоне клавиатуру: она закрывает
    // половину экрана, видимых строк остаётся вдвое меньше, и кадр обходится
    // дешевле, чем в работе. Такой замер льстит.
    val input = if (probe == null) {
        Modifier
            .imeInput(state)
            .focusRequester(focusRequester)
            .focusable()
            .editorKeyInput(state)
            .pointerInput(metrics) {
                detectTapGestures { position ->
                    val text = state.text
                    val gutter = gutterWidthPx(text.lineCount, metrics)
                    state.setCarets(
                        CaretSet.single(
                            offsetAt(text, metrics, gutter, scrollPx, scrollXPx, position) { line, x ->
                                lineLayout(line).getOffsetForPosition(Offset(x, 0f))
                            }
                        )
                    )
                    runCatching { focusRequester.requestFocus() }
                    // Тап по тексту — просьба печатать. Клавиатуру, закрытую
                    // кнопкой «назад», иначе не вернуть: фокус уже здесь,
                    // и сессия ввода не перезапустится сама.
                    keyboard?.show()
                }
            }
    } else {
        Modifier
    }

    if (probe == null) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    // Автопрокрутка стенда. Ключи включают metrics: при смене размера шрифта
    // меняется высота строки, а с ней и граница прокрутки — эффект со старой
    // высотой крутил бы за пределы документа.
    if (probe != null && probe.autoScrollPxPerSecond > 0f) {
        LaunchedEffect(probe, state, metrics) {
            var previous = 0L
            while (true) {
                withFrameNanos { now ->
                    if (previous != 0L) {
                        val limit = maxScrollPx(state, metrics, viewportHeight)
                        val step = probe.autoScrollPxPerSecond * (now - previous) / 1_000_000_000f
                        // Дойдя до конца, начинаем сначала: замер идёт непрерывно.
                        scrollPx = if (scrollPx + step >= limit) 0f else scrollPx + step
                    }
                    previous = now
                }
            }
        }
    }

    Box(modifier.background(colors.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .scrollable(scrollState, Orientation.Vertical)
                .scrollable(horizontalScrollState, Orientation.Horizontal)
                .then(zoom)
                .then(input)
        ) {
            viewportHeight = size.height.roundToInt()
            viewportWidth = size.width.roundToInt()
            // Чтение ревизии в фазе отрисовки — это подписка: правка перерисует
            // холст, не трогая пересборку.
            revision.longValue
            widestLinePx = max(
                widestLinePx,
                drawEditor(
                    state = state,
                    lineLayout = lineLayout,
                    measurer = measurer,
                    style = style,
                    cache = cache,
                    colors = colors,
                    metrics = metrics,
                    gutterWidth = gutterWidthPx(state.text.lineCount, metrics),
                    scrollPx = scrollPx,
                    scrollXPx = scrollXPx,
                    caretVisible = caretVisible,
                    search = search,
                ),
            )
            probe?.let {
                it.cacheHits = cache.hits
                it.cacheMisses = cache.misses
                it.firstVisibleLine = (scrollPx / metrics.height).toInt()
            }
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

internal fun gutterWidthPx(lineCount: Int, metrics: LineMetrics): Float {
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

/** Рисует видимые строки и возвращает ширину самой широкой из них. */
private fun DrawScope.drawEditor(
    state: EditorState,
    lineLayout: (Int) -> TextLayoutResult,
    measurer: TextMeasurer,
    style: TextStyle,
    cache: LineLayoutCache<TextLayoutResult>,
    colors: EditorColors,
    metrics: LineMetrics,
    gutterWidth: Float,
    scrollPx: Float,
    scrollXPx: Float,
    caretVisible: Boolean,
    search: SearchSession?,
): Float {
    val text = state.text
    if (metrics.height <= 0f) return 0f

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

    drawRect(colors.gutterBackground, size = Size(gutterWidth, size.height))

    val caretLines = state.carets.carets.map { text.lineOf(it.head) }.toSet()

    // Правый край видимой части в координатах текста: выделение, захватившее
    // перенос строки, тянется до него, а не до края холста — иначе при сдвиге
    // вправо полоса обрывается посреди экрана.
    val rightEdge = scrollXPx + size.width
    var widest = 0f

    // Текст обрезается по гаттеру: уезжая влево, он обязан скрываться под ним,
    // а не поверх номеров строк.
    clipRect(left = gutterWidth) {
        translate(left = -scrollXPx, top = -scrollPx) {
            for (line in first until last) {
                val top = line * metrics.height
                val lineStart = text.lineStart(line)
                val lineEnd = text.lineEnd(line)

                val layout = lineLayout(line)
                widest = max(widest, layout.size.width.toFloat())

                for (match in matches) {
                    drawRange(
                        colors.searchMatch, metrics, gutterWidth, layout,
                        lineStart, lineEnd, top, match.start, match.end, rightEdge,
                    )
                }

                drawSelection(
                    state, colors, metrics, gutterWidth, layout,
                    lineStart, lineEnd, top, rightEdge,
                )

                // Без color: цвета берутся из кусков разметки, а перекрытие сверху
                // покрасило бы всю строку одинаково.
                drawText(layout, topLeft = Offset(gutterWidth, top))

                if (caretVisible) {
                    drawCarets(state, colors, metrics, gutterWidth, layout, lineStart, lineEnd, top)
                }
            }
        }
    }

    // Гаттер рисуется отдельно и без горизонтального сдвига: это неподвижный
    // столбец, и уезжать вместе с текстом он не должен.
    translate(top = -scrollPx) {
        for (line in first until last) {
            val number = (line + 1).toString()
            val numberLayout = cache.get(GUTTER_KEY_PREFIX + number) { measurer.measure(number, style) }
            drawText(
                numberLayout,
                color = if (line in caretLines) colors.currentLineGutterText else colors.gutterText,
                topLeft = Offset(
                    gutterWidth - numberLayout.size.width - metrics.digitWidth,
                    line * metrics.height,
                ),
            )
        }
    }

    return widest
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
    rightEdge: Float,
) {
    for (caret in state.carets.carets) {
        if (caret.isEmpty) continue
        drawRange(
            colors.selection, metrics, gutterWidth, layout,
            lineStart, lineEnd, top, caret.start, caret.end, rightEdge,
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
    rightEdge: Float,
) {
    if (to < lineStart || from > lineEnd) return

    val length = layout.layoutInput.text.length
    val start = (from.coerceAtLeast(lineStart) - lineStart).coerceIn(0, length)
    val end = (to.coerceAtMost(lineEnd) - lineStart).coerceIn(0, length)

    val left = gutterWidth + layout.getHorizontalPosition(start, usePrimaryDirection = true)
    val right = gutterWidth + layout.getHorizontalPosition(end, usePrimaryDirection = true)
    // Диапазон, захвативший перенос строки, тянем до края: иначе не видно, что
    // выбрана строка целиком.
    val extended = if (to > lineEnd) rightEdge else right

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

/**
 * Строка с расставленными цветами.
 *
 * `AnnotatedString` строится только при промахе кэша разметки: на попадании
 * ничего не считается вообще, а попаданий при прокрутке около 98% (P2).
 */
private fun highlighted(
    line: String,
    stateBefore: Int,
    highlighter: LineHighlighter,
    colors: EditorColors,
): AnnotatedString {
    val spans = highlighter.highlight(line, stateBefore).spans
    if (spans.isEmpty()) return AnnotatedString(line)

    return buildAnnotatedString {
        append(line)
        for (span in spans) {
            val color = colors.syntax[span.kind] ?: continue
            addStyle(
                SpanStyle(color = color),
                span.start.coerceIn(0, line.length),
                span.end.coerceIn(0, line.length),
            )
        }
    }
}

/** Куда поставить курсор по касанию. */
internal fun offsetAt(
    text: Rope,
    metrics: LineMetrics,
    gutterWidth: Float,
    scrollPx: Float,
    scrollXPx: Float,
    position: Offset,
    columnAt: (line: Int, x: Float) -> Int,
): Int {
    val line = ((position.y + scrollPx) / metrics.height).toInt().coerceIn(0, text.lineCount - 1)
    val lineStart = text.lineStart(line)
    val lineEnd = text.lineEnd(line)

    // Колонку определяет разметка строки, а не ширина цифры: табуляция и
    // иероглиф шире цифры, и арифметика по одной ширине на них уезжает.
    val x = position.x - gutterWidth + scrollXPx
    val column = columnAt(line, x.coerceAtLeast(0f))
    return (lineStart + column).coerceIn(lineStart, lineEnd)
}

/** Левая и правая границы курсора в координатах строки. */
internal data class CaretBounds(val left: Float, val right: Float)

/** Где стоит основной курсор по горизонтали — по разметке его строки. */
internal fun caretBounds(state: EditorState, lineLayout: (Int) -> TextLayoutResult): CaretBounds {
    val text = state.text
    val head = state.carets.primary.head
    val line = text.lineOf(head)
    val layout = lineLayout(line)
    val column = (head - text.lineStart(line)).coerceIn(0, layout.layoutInput.text.length)

    val left = layout.getHorizontalPosition(column, usePrimaryDirection = true)
    // Правая граница — следующая позиция, если она есть: курсор должен въезжать
    // в окно целиком, а не краем.
    val right = if (column < layout.layoutInput.text.length) {
        layout.getHorizontalPosition(column + 1, usePrimaryDirection = true)
    } else {
        left
    }
    return CaretBounds(left, right)
}

/** Сдвигает по горизонтали так, чтобы курсор остался виден. */
internal fun scrollXToCaret(caret: CaretBounds, scrollXPx: Float, textWidth: Float): Float {
    if (textWidth <= 0f) return scrollXPx

    return when {
        caret.left < scrollXPx -> caret.left
        caret.right > scrollXPx + textWidth -> caret.right - textWidth
        else -> scrollXPx
    }
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
