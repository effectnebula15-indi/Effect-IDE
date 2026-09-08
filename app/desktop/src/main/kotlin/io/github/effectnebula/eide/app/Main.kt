package io.github.effectnebula.eide.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.github.effectnebula.eide.platform.desktop.DesktopEnvironment
import io.github.effectnebula.eide.ui.RenderBenchmark
import io.github.effectnebula.eide.ui.benchmarkDocument
import kotlin.system.exitProcess

/**
 * Десктопная сборка. Сейчас показывает стенд прототипа P2: тот же рендер текста,
 * что и на телефоне, с теми же счётчиками кадров.
 *
 * Смысл десктопной сборки на этом этапе — чтобы результат было видно без телефона:
 * правило «сначала десктоп, потом Android» существует ровно поэтому.
 */
fun main() = application {
    // Ошибка окружения, которую иначе замечают только по испорченным именам файлов.
    DesktopEnvironment.fileNameWarning()?.let { System.err.println("ВНИМАНИЕ: $it") }

    val document = benchmarkDocument()

    // Прогон без человека: -Deide.benchmarkSeconds=15 печатает счётчики в вывод
    // и закрывает окно. Так цифры можно получить на машине без экрана.
    val benchmarkSeconds = System.getProperty("eide.benchmarkSeconds")?.toIntOrNull()

    Window(
        onCloseRequest = ::exitApplication,
        title = "Effect IDE · стенд отрисовки",
        state = WindowState(size = DpSize(1100.dp, 720.dp)),
    ) {
        RenderBenchmark(
            document = document,
            reporter = benchmarkSeconds?.let { limit ->
                { report ->
                    println(
                        "секунда=%d fps=%.1f худший=%.1fмс просадок=%d/%d разметка=%d/%d".format(
                            report.second, report.fps, report.worstFrameMs,
                            report.jankFrames, report.totalFrames,
                            report.cacheHits, report.cacheHits + report.cacheMisses,
                        )
                    )
                    if (report.second >= limit) exitProcess(0)
                }
            },
        )
    }
}
