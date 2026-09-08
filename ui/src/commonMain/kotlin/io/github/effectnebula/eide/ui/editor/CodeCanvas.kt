package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.theme.Eide
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Отрисовка текста с виртуализацией: рисуются только видимые строки.
 *
 * Это ядро риска R3 из плана. Если этот код не держит 60 кадров в секунду на
 * телефоне, придётся пересматривать ADR-001 — то есть выбор Compose как основы.
 *
 * Виртуализация здесь буквальная: из документа в миллион строк на кадре трогается
 * ровно столько, сколько помещается на экране. Всё остальное не существует для
 * отрисовки — ни как composable-узлы, ни как объекты разметки.
 */
@Composable
fun CodeCanvas(
    document: Rope,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFFBCBEC4),
    background: Color = Color(0xFF1E1F22),
    fontSizeSp: Float = 13f,
    /**
     * Скорость автопрокрутки в пикселях в секунду. Ноль — прокрутка только пальцем.
     *
     * Нужна для измерений: результат, зависящий от того, как рукой водили по экрану,
     * невозможно сравнить с предыдущим.
     */
    autoScrollPxPerSecond: Float = 0f,
    stats: CodeCanvasStats? = null,
) {
    val measurer = rememberTextMeasurer()
    // Шрифт тот же, что в редакторе: замер отрисовки другим шрифтом меряет не то,
    // что потом увидит человек — ширина глифов и хинтинг у шрифтов разные.
    val font = Eide.editorFont
    val style = remember(fontSizeSp, font) {
        TextStyle(fontSize = fontSizeSp.sp, fontFamily = font)
    }

    // Кэш переживает пересборку и правки документа: ключ — текст строки.
    val cache = remember { LineLayoutCache<TextLayoutResult>() }

    val lineHeightPx = remember(style, measurer) {
        // Высота строки постоянна: шрифт моноширинный, а перенос строк мы не делаем.
        measurer.measure("Xg", style).size.height.toFloat()
    }

    var scrollPx by remember { mutableFloatStateOf(0f) }
    val maxScrollPx = max(0f, document.lineCount * lineHeightPx - 1)

    LaunchedEffect(autoScrollPxPerSecond, maxScrollPx) {
        if (autoScrollPxPerSecond <= 0f || maxScrollPx <= 0f) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    val advanced = scrollPx + autoScrollPxPerSecond * (now - previous) / 1_000_000_000f
                    // Дойдя до конца, начинаем сначала: замер идёт непрерывно.
                    scrollPx = if (advanced >= maxScrollPx) 0f else advanced
                }
                previous = now
            }
        }
    }

    val scrollState = rememberScrollableState { delta ->
        val previous = scrollPx
        scrollPx = (scrollPx - delta).coerceIn(0f, maxScrollPx)
        previous - scrollPx
    }

    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxSize()
            .scrollable(scrollState, Orientation.Vertical)
    ) {
        drawRect(background)
        drawVisibleLines(
            document = document,
            measurer = measurer,
            style = style.copy(color = textColor),
            cache = cache,
            lineHeightPx = lineHeightPx,
            scrollPx = scrollPx,
        )
        stats?.let {
            it.cacheHits = cache.hits
            it.cacheMisses = cache.misses
            it.firstVisibleLine = (scrollPx / lineHeightPx).toInt()
        }
    }
}

/** Наблюдаемые изнутри цифры — нужны прототипу измерения, не самому редактору. */
class CodeCanvasStats {
    var cacheHits: Int = 0
        internal set
    var cacheMisses: Int = 0
        internal set
    var firstVisibleLine: Int = 0
        internal set
}

private fun DrawScope.drawVisibleLines(
    document: Rope,
    measurer: TextMeasurer,
    style: TextStyle,
    cache: LineLayoutCache<TextLayoutResult>,
    lineHeightPx: Float,
    scrollPx: Float,
) {
    if (lineHeightPx <= 0f) return

    val first = (scrollPx / lineHeightPx).toInt().coerceAtLeast(0)
    val visible = (size.height / lineHeightPx).roundToInt() + 2
    val last = min(first + visible, document.lineCount)

    // Ширина холста ограничивает разметку: измерять строку в бесконечной ширине
    // дороже, а видно всё равно только то, что помещается.
    val constraints = Constraints(maxWidth = size.width.roundToInt().coerceAtLeast(1))

    translate(top = -scrollPx) {
        for (line in first until last) {
            val text = document.substring(document.lineStart(line), document.lineEnd(line))
            val layout = cache.get(text) { measurer.measure(it, style, constraints = constraints) }
            drawText(layout, topLeft = Offset(0f, line * lineHeightPx))
        }
    }
}
