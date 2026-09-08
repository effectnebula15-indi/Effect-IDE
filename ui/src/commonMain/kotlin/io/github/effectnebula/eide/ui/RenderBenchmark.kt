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
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.syntax.Highlighters
import io.github.effectnebula.eide.core.syntax.LineHighlighter
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.GraphemeBreaker
import io.github.effectnebula.eide.ui.editor.CodeEditor
import io.github.effectnebula.eide.ui.editor.RenderProbe
import io.github.effectnebula.eide.ui.editor.rememberFrameMeter
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Стенд для прототипа P2: измеряет, держит ли собственный рендер текста 60 кадров
 * в секунду. Общий для Android и десктопа — цифры на телефоне и на компьютере
 * получаются одним и тем же кодом, и их можно сравнивать.
 *
 * Рисует настоящий [CodeEditor], а не упрощённую копию: гаттер, курсор, подсветку.
 * Замер на копии показывал бы стоимость кода, которого пользователь не видит.
 *
 * Ввод в стенде отключён (это делает сам редактор, получив `probe`): на телефоне
 * клавиатура закрыла бы половину строк и удешевила кадр.
 */
@Composable
fun RenderBenchmark(
    state: EditorState,
    modifier: Modifier = Modifier,
    initialFontSizeSp: Float = 13f,
    initialHighlight: Boolean = true,
    /**
     * Куда сообщать замеры, кроме экрана. Нужен для прогона без человека:
     * запустить, подождать, прочитать цифры из вывода.
     */
    reporter: ((FrameReport) -> Unit)? = null,
) {
    var autoScroll by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(initialFontSizeSp) }
    var highlight by remember { mutableStateOf(initialHighlight) }
    val meter = rememberFrameMeter(enabled = autoScroll)

    // Новый probe на каждое переключение прокрутки: скорость в нём неизменяемая,
    // и смена объекта — это и есть перезапуск эффекта автопрокрутки в редакторе.
    val probe = remember(autoScroll) {
        RenderProbe(if (autoScroll) SCROLL_PX_PER_SECOND else 0f)
    }

    // Подсветку можно снять переключателем: это единственный способ разделить
    // стоимость лексера и стоимость шейпинга, не собирая отдельную сборку.
    val highlighter = remember(highlight) {
        if (highlight) Highlighters.forFile(BENCHMARK_FILE_NAME) else LineHighlighter.None
    }

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
                        cacheHits = probe.cacheHits,
                        cacheMisses = probe.cacheMisses,
                        fontSizeSp = fontSize,
                        highlighted = highlight,
                    )
                )
                if (seconds >= REPORT_LIMIT_SECONDS) return@LaunchedEffect
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
            Chip(if (highlight) "с подсветкой" else "без подсветки") { highlight = !highlight }
            Chip("сброс") { meter.reset() }
        }

        Row(
            Modifier.fillMaxWidth().background(Eide.colors.border).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Readout("fps", "%.1f".format(meter.fps))
            Readout("худший кадр", "%.1f мс".format(meter.worstFrameMs))
            Readout("просадки", "${meter.jankFrames} из ${meter.totalFrames}")
            Readout("разметка", "${probe.cacheHits}/${probe.cacheHits + probe.cacheMisses}")
            // Номер строки нужен не для красоты: без него отличить «60 fps при
            // прокрутке» от «60 fps на замершей картинке» нельзя.
            Readout("строка", "${probe.firstVisibleLine}")
        }

        CodeEditor(
            state = state,
            colors = editorColors(),
            modifier = Modifier.fillMaxSize(),
            fontSizeSp = fontSize,
            highlighter = highlighter,
            probe = probe,
        )
    }
}

/** Скорость автопрокрутки: примерно экран телефона в секунду. */
private const val SCROLL_PX_PER_SECOND = 900f

/**
 * Сколько секунд стенд отчитывается наблюдателю.
 *
 * Ограничение есть потому, что безусловный `while (true)` в прогоне без человека
 * держит приложение живым и после того, как вызывающая сторона наигралась.
 */
private const val REPORT_LIMIT_SECONDS = 600

private const val BENCHMARK_FILE_NAME = "benchmark.py"

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

/**
 * Снимок счётчиков за секунду работы стенда.
 *
 * Размер шрифта и подсветка входят в отчёт не для полноты: замеры при разных
 * настройках отличаются в разы, и строка вывода без них не говорит, к чему
 * относится число.
 */
data class FrameReport(
    val second: Int,
    val fps: Float,
    val worstFrameMs: Float,
    val jankFrames: Int,
    val totalFrames: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val fontSizeSp: Float,
    val highlighted: Boolean,
)

/**
 * Редактор с синтетическим документом для замеров.
 *
 * Состояние строится здесь, а не в приложениях: стенду нужен ровно тот же
 * `EditorState`, что и рабочему редактору, а платформенный [GraphemeBreaker]
 * знают только приложения — поэтому он и передаётся снаружи.
 */
fun benchmarkEditor(graphemes: GraphemeBreaker, lines: Int = 5_000): EditorState =
    EditorState(Document(benchmarkDocument(lines)), graphemes)

/**
 * Синтетический документ для замеров: строки разной длины, чтобы разметка не
 * сводилась к одной закэшированной, и достаточно длинный, чтобы прокрутка была
 * настоящей.
 *
 * Текст питоновский намеренно: подсветка должна находить в нём ключевые слова,
 * имена, числа и комментарии. На тексте, в котором лексеру нечего делать, замер
 * соврал бы в свою пользу.
 */
private fun benchmarkDocument(lines: Int): Rope {
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
