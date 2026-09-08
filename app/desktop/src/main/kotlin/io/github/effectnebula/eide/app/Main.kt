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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import io.github.effectnebula.eide.core.project.ProjectTree
import io.github.effectnebula.eide.core.project.Workspace
import io.github.effectnebula.eide.platform.desktop.DesktopEnvironment
import io.github.effectnebula.eide.platform.desktop.JdkGraphemeBreaker
import io.github.effectnebula.eide.ui.EditorScreen
import io.github.effectnebula.eide.ui.RenderBenchmark
import io.github.effectnebula.eide.ui.benchmarkDocument
import io.github.effectnebula.eide.ui.project.FileTabs
import io.github.effectnebula.eide.ui.project.FileTreePanel
import io.github.effectnebula.eide.ui.theme.Eide
import io.github.effectnebula.eide.ui.theme.LocalEditorFont
import androidx.compose.runtime.CompositionLocalProvider
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO
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
        // Самоснимок: -Deide.screenshot=/путь.png сохраняет окно и выходит.
        // Существует потому, что интерфейс пишется вслепую — без этого о нём
        // можно судить только по тому, что сборка не упала.
        System.getProperty("eide.screenshot")?.let { path ->
            LaunchedEffect(Unit) {
                delay(SCREENSHOT_SETTLE_MS)
                captureScreen(File(path))
                exitProcess(0)
            }
        }

        CompositionLocalProvider(LocalEditorFont provides remember { jetBrainsMono() }) {
            if (benchmarkSeconds != null) {
                BenchmarkOnly(benchmarkSeconds)
            } else {
                DesktopShell()
            }
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

/**
 * Десктопная оболочка: дерево слева, редактор справа.
 *
 * Две колонки, а не одна панель за раз, как на телефоне: здесь ширины хватает,
 * и прятать дерево значило бы усложнять ровно там, где сложности нет.
 *
 * Проект — рабочий каталог, из которого запущено приложение. Выбора папки пока
 * нет: диалог — платформенная штука, а пользы от него на этом этапе меньше,
 * чем от возможности сразу увидеть настоящее дерево.
 */
@Composable
private fun DesktopShell() {
    var showBenchmark by remember { mutableStateOf(false) }

    val workspace = remember {
        // -Deide.project=/путь открывает чужую папку; по умолчанию — та, из
        // которой запущено приложение.
        val root = System.getProperty("eide.project") ?: System.getProperty("user.dir")
        Workspace(ProjectTree(File(root)), JdkGraphemeBreaker()).apply {
            // -Deide.open=путь открывает файл на старте. Нужно для самоснимка:
            // иначе увидеть редактор с вкладками можно только руками.
            System.getProperty("eide.open")?.let { open(File(root, it)) }
        }
    }
    var revision by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION")
    revision

    val active = workspace.active

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
            return@Column
        }

        Row(Modifier.weight(1f)) {
            Box(Modifier.width(TREE_WIDTH)) {
                FileTreePanel(
                    tree = workspace.tree,
                    selected = active?.file,
                    onOpen = { file ->
                        workspace.saveModified()
                        workspace.open(file)
                        revision++
                    },
                )
            }

            Column(Modifier.weight(1f)) {
                FileTabs(
                    files = workspace.files,
                    active = active,
                    onSelect = { workspace.activate(it.file); revision++ },
                    onClose = { workspace.saveModified(); workspace.close(it.file); revision++ },
                )

                if (active != null) {
                    EditorScreen(active.state, Modifier.weight(1f))
                } else {
                    Box(Modifier.weight(1f).padding(16.dp)) {
                        BasicText(
                            "выберите файл в дереве слева",
                            style = TextStyle(color = Eide.colors.textDim, fontSize = 13.sp),
                        )
                    }
                }
            }
        }
    }
}

/** Ширина колонки дерева: помещается путь средней длины, но не съедает редактор. */
private val TREE_WIDTH = 280.dp

/**
 * Пауза перед снимком: первый кадр Compose рисует не сразу, а под программной
 * растеризацией — тем более. Снимок пустого окна выглядел бы как поломка вёрстки.
 */
private const val SCREENSHOT_SETTLE_MS = 2_500L

/**
 * Снимок всего экрана, а не окна.
 *
 * Координаты окна под виртуальным X-сервером приходят нулевыми, хотя рисуется
 * оно со смещением: снимок по ним съезжает и режет край. Экран целиком не
 * съезжает никогда.
 */
private fun captureScreen(target: File) {
    runCatching {
        val screen = Toolkit.getDefaultToolkit().screenSize
        val image = Robot().createScreenCapture(Rectangle(0, 0, screen.width, screen.height))
        ImageIO.write(image, "png", target)
        println("снимок экрана: ${target.absolutePath}")
    }.onFailure {
        System.err.println("снимок не получился: $it")
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
