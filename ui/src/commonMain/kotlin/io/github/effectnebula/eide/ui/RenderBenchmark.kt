package io.github.effectnebula.eide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.editor.CodeCanvas
import io.github.effectnebula.eide.ui.editor.CodeCanvasStats
import io.github.effectnebula.eide.ui.editor.rememberFrameMeter
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Стенд для прототипа P2: измеряет, держит ли собственный рендер текста 60 кадров
 * в секунду. Общий для Android и десктопа — цифры на телефоне и на компьютере
 * получаются одним и тем же кодом, и их можно сравнивать.
 */
@Composable
fun RenderBenchmark(
    document: Rope,
    modifier: Modifier = Modifier,
    /**
     * Куда сообщать замеры, кроме экрана. Нужен для прогона без человека:
     * запустить, подождать, прочитать цифры из вывода.
     */
    reporter: ((FrameReport) -> Unit)? = null,
) {
    var autoScroll by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(13f) }
    val meter = rememberFrameMeter(enabled = autoScroll)
    val stats = remember { CodeCanvasStats() }

    if (reporter != null) {
        LaunchedEffect(Unit) {
            var seconds = 0
            while (true) {
                kotlinx.coroutines.delay(1_000)
                seconds++
                reporter(
                    FrameReport(
                        second = seconds,
                        fps = meter.fps,
                        worstFrameMs = meter.worstFrameMs,
                        jankFrames = meter.jankFrames,
                        totalFrames = meter.totalFrames,
                        cacheHits = stats.cacheHits,
                        cacheMisses = stats.cacheMisses,
                    )
                )
            }
        }
    }

    Column(modifier.fillMaxSize().background(Eide.colors.background)) {
        Row(
            Modifier.fillMaxWidth().background(Eide.colors.panel).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(if (autoScroll) "прокрутка идёт" else "прокрутка стоит") { autoScroll = !autoScroll }
            Chip("шрифт ${fontSize.toInt()}") {
                fontSize = if (fontSize >= 20f) 10f else fontSize + 2f
            }
            Chip("сброс") { meter.reset() }
        }

        Row(
            Modifier.fillMaxWidth().background(Eide.colors.border).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Readout("fps", "%.1f".format(meter.fps))
            Readout("худший кадр", "%.1f мс".format(meter.worstFrameMs))
            Readout("просадки", "${meter.jankFrames} из ${meter.totalFrames}")
            Readout("разметка", "${stats.cacheHits}/${stats.cacheHits + stats.cacheMisses}")
        }

        CodeCanvas(
            document = document,
            modifier = Modifier.fillMaxSize(),
            textColor = Eide.colors.text,
            background = Eide.colors.background,
            fontSizeSp = fontSize,
            autoScrollPxPerSecond = if (autoScroll) 900f else 0f,
            stats = stats,
        )
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Eide.colors.border)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        BasicText(label, style = TextStyle(color = Eide.colors.text, fontSize = 12.sp))
    }
}

@Composable
private fun Readout(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = TextStyle(color = Eide.colors.textDim, fontSize = 11.sp))
        BasicText(
            value,
            style = TextStyle(
                color = Eide.colors.text,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

/** Снимок счётчиков за секунду работы стенда. */
data class FrameReport(
    val second: Int,
    val fps: Float,
    val worstFrameMs: Float,
    val jankFrames: Int,
    val totalFrames: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
)

/**
 * Синтетический документ для замеров: строки разной длины, чтобы разметка не
 * сводилась к одной закэшированной, и достаточно длинный, чтобы прокрутка была
 * настоящей.
 */
fun benchmarkDocument(lines: Int = 5_000): Rope {
    val builder = StringBuilder(lines * 60)
    for (i in 0 until lines) {
        builder.append("def function_")
            .append(i)
            .append("(argument, other=")
            .append(i % 97)
            .append("):  # строка ")
            .append(i)
            .append(", длина меняется ")
            .append("=".repeat(i % 40))
            .append('\n')
    }
    return Rope.of(builder.toString())
}
