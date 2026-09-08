package io.github.effectnebula.eide.app

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.platform.desktop.DesktopEnvironment
import io.github.effectnebula.eide.platform.desktop.JdkGraphemeBreaker
import io.github.effectnebula.eide.ui.EditorScreen
import io.github.effectnebula.eide.ui.RenderBenchmark
import io.github.effectnebula.eide.ui.benchmarkDocument
import io.github.effectnebula.eide.ui.sampleDocumentText
import io.github.effectnebula.eide.ui.theme.Eide
import kotlin.system.exitProcess

/**
 * Десктопная сборка: редактор и стенд замеров отрисовки.
 *
 * Правило «сначала десктоп, потом Android» существует ровно для того, чтобы
 * результат было видно без телефона.
 */
fun main() = application {
    // Ошибка окружения, которую иначе замечают только по испорченным именам файлов.
    DesktopEnvironment.fileNameWarning()?.let { System.err.println("ВНИМАНИЕ: $it") }

    // Прогон без человека: -Deide.benchmarkSeconds=15 печатает счётчики и выходит.
    val benchmarkSeconds = System.getProperty("eide.benchmarkSeconds")?.toIntOrNull()

    Window(
        onCloseRequest = ::exitApplication,
        title = "Effect IDE",
        state = WindowState(size = DpSize(1100.dp, 720.dp)),
    ) {
        if (benchmarkSeconds != null) {
            BenchmarkOnly(benchmarkSeconds)
        } else {
            DesktopShell()
        }
    }
}

@Composable
private fun BenchmarkOnly(seconds: Int) {
    val document = remember { benchmarkDocument() }
    RenderBenchmark(
        document = document,
        reporter = { report ->
            println(
                "секунда=%d fps=%.1f худший=%.1fмс просадок=%d/%d разметка=%d/%d".format(
                    report.second, report.fps, report.worstFrameMs,
                    report.jankFrames, report.totalFrames,
                    report.cacheHits, report.cacheHits + report.cacheMisses,
                )
            )
            if (report.second >= seconds) exitProcess(0)
        },
    )
}

@Composable
private fun DesktopShell() {
    var showBenchmark by remember { mutableStateOf(false) }
    val editorState = remember {
        EditorState(Document(Rope.of(sampleDocumentText())), JdkGraphemeBreaker())
    }

    Column(Modifier.fillMaxSize().background(Eide.colors.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Eide.colors.border)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab("редактор", !showBenchmark) { showBenchmark = false }
            Tab("отрисовка · P2", showBenchmark) { showBenchmark = true }
        }

        if (showBenchmark) {
            val document = remember { benchmarkDocument() }
            RenderBenchmark(document, Modifier.weight(1f))
        } else {
            EditorScreen(editorState, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tab(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Eide.colors.accent else Eide.colors.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicText(
            title,
            style = TextStyle(
                color = if (selected) Color.White else Eide.colors.textDim,
                fontSize = 12.sp,
            ),
        )
    }
}
