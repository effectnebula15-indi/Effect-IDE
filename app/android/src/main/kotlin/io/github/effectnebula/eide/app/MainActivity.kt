package io.github.effectnebula.eide.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.effectnebula.eide.core.exec.KillReason
import io.github.effectnebula.eide.core.exec.RunHandle
import io.github.effectnebula.eide.core.exec.RunListener
import io.github.effectnebula.eide.core.exec.RunLimits
import io.github.effectnebula.eide.core.exec.RunSpec
import io.github.effectnebula.eide.core.editor.SearchSession
import io.github.effectnebula.eide.core.syntax.Highlighters
import io.github.effectnebula.eide.core.project.OpenFile
import io.github.effectnebula.eide.core.project.ProjectTree
import io.github.effectnebula.eide.core.project.Workspace
import io.github.effectnebula.eide.platform.android.IcuGraphemeBreaker
import io.github.effectnebula.eide.runner.android.AndroidPythonBackend
import io.github.effectnebula.eide.runner.android.CanvasArea
import io.github.effectnebula.eide.ui.EditorScreen
import io.github.effectnebula.eide.ui.RenderBenchmark
import io.github.effectnebula.eide.ui.benchmarkEditor
import io.github.effectnebula.eide.ui.editor.AutoSave
import io.github.effectnebula.eide.ui.editor.ExtraKeyRow
import io.github.effectnebula.eide.ui.editorColors
import io.github.effectnebula.eide.ui.project.FileTabs
import io.github.effectnebula.eide.ui.project.FileTreePanel
import io.github.effectnebula.eide.ui.run.OutputPanel
import io.github.effectnebula.eide.ui.search.SearchBar
import io.github.effectnebula.eide.ui.theme.LocalEditorFont
import io.github.effectnebula.eide.ui.widgets.NoticeBar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Приложение целиком: редактор, запуск того, что в нём написано, и стенд замеров.
 *
 * Редактор и запуск связаны настоящим файлом, а не двумя копиями текста в памяти:
 * иначе «запустилось не то, что видно на экране» — вопрос времени.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 означает edge-to-edge принудительно: окно больше не
        // ужимается под клавиатуру само, и windowSoftInputMode=adjustResize
        // этого не вернёт. Отступы считаем сами, через safeDrawingPadding.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

private val Background = Color(0xFF1E1F22)
private val Panel = Color(0xFF2B2D30)
private val Border = Color(0xFF393B40)
private val TextColor = Color(0xFFBCBEC4)
private val TextDim = Color(0xFF6F737A)
private val Accent = Color(0xFF3574F0)
private val Danger = Color(0xFFDB5C5C)

private val mainHandler = Handler(Looper.getMainLooper())

private val PROTOTYPE_LIMITS = RunLimits(
    timeoutMillis = 10_000,
    maxResidentBytes = 256L * 1024 * 1024,
)

private enum class Screen { Project, Code, Render }

@Composable
private fun App() {
    val context = LocalContext.current
    val font = remember(context) { jetBrainsMono(context.assets) }

    CompositionLocalProvider(LocalEditorFont provides font) { Shell() }
}

@Composable
private fun Shell() {
    var screen by remember { mutableStateOf(Screen.Code) }

    // Область кадров переживает несколько запусков: раннер одноразовый, а
    // канва — нет. Создаётся один раз на всё приложение.
    val canvas = remember { runCatching { CanvasArea.create() }.getOrNull() }
    DisposableEffect(canvas) { onDispose { canvas?.close() } }

    var showCanvas by remember { mutableStateOf(false) }

    // Взводится на Run и снимается первым же показом. Без этого свайп к коду
    // был бы бесполезен: следующий кадр немедленно вернул бы на графику, и с
    // работающей программой до редактора не добраться.
    var awaitingFirstFrame by remember { mutableStateOf(false) }

    /*
     * Переключаемся на графику сами, когда программа нарисовала первый кадр.
     * Заранее знать, графическая ли она, нельзя, а заставлять жать вторую
     * кнопку после Run — значит, что первый запуск выглядит как «ничего не
     * произошло». Проверка дешёвая: номер кадра читается без копирования.
     */
    LaunchedEffect(canvas, awaitingFirstFrame) {
        if (canvas == null || !awaitingFirstFrame) return@LaunchedEffect
        val before = canvas.latestFrame()
        while (true) {
            withFrameNanos { }
            if (canvas.latestFrame() > before) {
                awaitingFirstFrame = false
                showCanvas = true
                return@LaunchedEffect
            }
        }
    }

    if (showCanvas && canvas != null) {
        // Полный экран без вкладок и без системных отступов: графика занимает
        // всё, свайп возвращает к коду, программа продолжает работать.
        CanvasScreen(canvas, onBack = { showCanvas = false })
        return
    }

    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().background(Border).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab("проект", screen == Screen.Project) { screen = Screen.Project }
            Tab("код", screen == Screen.Code) { screen = Screen.Code }
            Tab("отрисовка · P2", screen == Screen.Render) { screen = Screen.Render }
        }

        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Project, Screen.Code -> WorkbenchScreen(
                    showTree = screen == Screen.Project,
                    onFileOpened = { screen = Screen.Code },
                    canvas = canvas,
                    onShowCanvas = { showCanvas = true },
                    onRunStarted = { awaitingFirstFrame = true },
                )
                Screen.Render -> {
                    // Документ строится один раз: пересборка на кадре испортила бы замер.
                    val editor = remember { benchmarkEditor(IcuGraphemeBreaker()) }
                    RenderBenchmark(editor)
                }
            }
        }
    }
}

/**
 * Проект, редактор и вывод программы.
 *
 * Одна панель за раз, а не три колонки: на телефоне колонки превращаются в
 * полоски, в которых ничего не прочесть (Шаг 3). Дерево и редактор
 * переключаются вкладками сверху, состояние обоих при этом живёт дальше.
 *
 * Прототипы P1 и P4 живут здесь же: Run запускает то, что сейчас в редакторе,
 * Stop убивает процесс, сторожевые лимиты снимают зависшую программу.
 */
@Composable
private fun WorkbenchScreen(
    showTree: Boolean,
    onFileOpened: () -> Unit,
    canvas: CanvasArea?,
    onShowCanvas: () -> Unit,
    onRunStarted: () -> Unit,
) {
    val context = LocalContext.current

    val projectDir = remember {
        File(context.filesDir, "projects/demo").apply { mkdirs() }
    }
    val scriptFile = remember {
        // Первый запуск: кладём два примера, чтобы было что запустить сразу и
        // чтобы дерево с вкладками показывали не один файл.
        File(projectDir, "console.py").apply { if (!exists()) writeText(CONSOLE_PROGRAM) }
        File(projectDir, "main.py").apply { if (!exists()) writeText(SAMPLE_PROGRAM) }
    }

    // Открытие завёрнуто намеренно: бросок отсюда пересчитывает remember на
    // каждой попытке композиции, а композиция после броска повторяется —
    // приложение зависает молча, без экрана и без сообщения.
    val startup = remember {
        val workspace = Workspace(ProjectTree(projectDir), IcuGraphemeBreaker())
        val failure = runCatching { workspace.open(scriptFile) }
            .fold({ null }, { "не открылся ${scriptFile.name}: ${it.message}" })
        workspace to failure
    }
    val workspace = startup.first
    var notice by remember { mutableStateOf(startup.second) }

    // Workspace — обычный объект, снапшот-система Compose за ним не следит.
    // Счётчик поднимается на каждое открытие, закрытие и переключение: то же
    // решение, что и для EditorState, и по той же причине.
    var workspaceRevision by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION")
    workspaceRevision

    val active: OpenFile? = workspace.active

    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("готов") }
    var handle by remember { mutableStateOf<RunHandle?>(null) }

    // Сессия поиска живёт вместе с файлом: закрыли файл — забыли запрос.
    val search = active?.let { file -> remember(file) { SearchSession(file.state) } }
    var showSearch by remember(active) { mutableStateOf(false) }

    /** Пишет всё изменённое синхронно. Возвращает активный файл, если он есть. */
    fun saveNow(): OpenFile? {
        workspace.saveModified()
        return workspace.active
    }

    if (active != null) {
        AutoSave(active.state) {
            // Снимок берётся в главном потоке, а пишется в фоновом: rope
            // неизменяем, поэтому снимок бесплатен и не разъедется с тем, что
            // человек печатает дальше.
            withContext(Dispatchers.IO) { workspace.save(active) }
            workspaceRevision++
        }
    }

    // Уход в фон — последний надёжный момент: дальше система вправе убить процесс
    // без предупреждения, и обещать асинхронную запись уже нельзя.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveNow() }

    fun onMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    fun run() {
        // Синхронно, а не через автосохранение: запускать надо ровно то, что
        // видно на экране, а не то, что успело записаться.
        val target = saveNow() ?: return
        workspaceRevision++

        output = ""
        status = "сохраняю и запускаю…"
        onRunStarted()

        val startedAt = System.currentTimeMillis()
        handle = AndroidPythonBackend(context, canvas).run(
            spec = RunSpec(
                script = target.file,
                workDir = projectDir,
                limits = PROTOTYPE_LIMITS,
            ),
            listener = object : RunListener {
                override fun onStarted(pid: Int) = onMain { status = "работает, процесс $pid" }
                override fun onStdout(chunk: String) = onMain { output += chunk }
                override fun onStderr(chunk: String) = onMain { output += chunk }

                override fun onExit(code: Int) = onMain {
                    output += "\n[завершилась с кодом $code за ${System.currentTimeMillis() - startedAt} мс]\n"
                    status = "готов"
                    handle = null
                }

                override fun onKilled(reason: KillReason) = onMain {
                    val why = when (reason) {
                        KillReason.ByUser -> "остановлена вручную"
                        KillReason.Timeout -> "снята по таймауту"
                        KillReason.Memory -> "снята по пределу памяти"
                    }
                    output += "\n[$why через ${System.currentTimeMillis() - startedAt} мс]\n"
                    status = "готов"
                    handle = null
                }

                override fun onFailure(error: Throwable) = onMain {
                    output += "\n[сбой канала: $error]\n"
                    status = "готов"
                    handle = null
                }
            },
        )
    }

    if (showTree) {
        FileTreePanel(
            tree = workspace.tree,
            selected = active?.file,
            onOpen = { file ->
                // Уходя с файла, дописываем его: секунда автосохранения могла
                // не наступить, а вернуться человек может нескоро.
                saveNow()
                // Файл мог исчезнуть между тем, как дерево его показало, и тем,
                // как по нему постучали пальцем.
                notice = runCatching { workspace.open(file) }
                    .fold({ null }, { "не открылся ${file.name}: ${it.message}" })
                workspaceRevision++
                onFileOpened()
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(Panel).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label(active?.name ?: "нет открытых файлов", TextColor, 13)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (search != null) {
                    Button("Найти", if (showSearch) Accent else Panel) { showSearch = !showSearch }
                }
                if (canvas != null) Button("Графика", Panel, onShowCanvas)
                val running = handle != null
                Button(if (running) "Stop" else "Run", if (running) Danger else Accent) {
                    if (running) handle?.stop() else if (active != null) run()
                }
            }
        }

        FileTabs(
            files = workspace.files,
            active = active,
            onSelect = { file ->
                saveNow()
                workspace.activate(file.file)
                workspaceRevision++
            },
            onClose = { file ->
                saveNow()
                workspace.close(file.file)
                workspaceRevision++
            },
        )

        notice?.let { NoticeBar(it) }

        if (showSearch && search != null) {
            SearchBar(
                session = search,
                onClose = { showSearch = false },
                onChanged = { workspaceRevision++ },
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (active != null) {
                EditorScreen(
                    active.state,
                    search = search,
                    highlighter = Highlighters.forFile(active.name),
                )
            } else {
                Label("откройте файл во вкладке «проект»", TextDim, 13)
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Border).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label(status, TextDim, 11)
            Label("предел ${PROTOTYPE_LIMITS.timeoutMillis} мс", TextDim, 11)
        }

        OutputPanel(output, Modifier.fillMaxWidth().weight(0.8f))

        // Самым нижним элементом: ряд должен быть вплотную к клавиатуре, иначе
        // до него не дотянуться большим пальцем, ради которого он и нужен.
        if (active != null) ExtraKeyRow(active.state, editorColors())
    }
}

private const val CONSOLE_PROGRAM = """import sys, platform

print("Python", sys.version.split()[0], "на", platform.machine())
for i in range(5):
    print("шаг", i)
print("готово")
"""

private const val SAMPLE_PROGRAM = """import eide

# Квадрат ездит по экрану. Кнопка «Графика» показывает результат на весь экран,
# свайп возвращает сюда — программа при этом продолжает работать.

canvas = eide.canvas()
clock = eide.Clock()

x = 0
step = 6

for frame in range(600):
    canvas.clear(0x101010FF)
    canvas.fill_rect(x, canvas.height // 2 - 40, 80, 80, 0xFF3B30FF)
    canvas.present()

    x += step
    if x <= 0 or x + 80 >= canvas.width:
        step = -step

    clock.tick(60)

print("нарисовано 600 кадров")
"""

@Composable
private fun Button(title: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Label(title, Color.White, 13)
    }
}

@Composable
private fun Tab(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Accent else Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Label(title, if (selected) Color.White else TextDim, 12)
    }
}

@Composable
private fun Label(text: String, color: Color, size: Int, mono: Boolean = false) {
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )
}
