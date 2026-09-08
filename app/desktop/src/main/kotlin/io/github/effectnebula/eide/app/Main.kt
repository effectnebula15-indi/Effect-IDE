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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.launch
import io.github.effectnebula.eide.core.exec.KillReason
import io.github.effectnebula.eide.core.exec.RunHandle
import io.github.effectnebula.eide.core.exec.RunLimits
import io.github.effectnebula.eide.core.exec.RunListener
import io.github.effectnebula.eide.core.exec.RunSpec
import io.github.effectnebula.eide.core.editor.SearchSession
import io.github.effectnebula.eide.core.syntax.Highlighters
import io.github.effectnebula.eide.core.project.ProjectTree
import io.github.effectnebula.eide.core.project.Workspace
import io.github.effectnebula.eide.platform.desktop.DesktopCanvasArea
import io.github.effectnebula.eide.platform.desktop.DesktopEnvironment
import io.github.effectnebula.eide.runner.LocalPythonBackend
import io.github.effectnebula.eide.platform.desktop.JdkGraphemeBreaker
import io.github.effectnebula.eide.ui.EditorScreen
import io.github.effectnebula.eide.ui.RenderBenchmark
import io.github.effectnebula.eide.ui.benchmarkEditor
import io.github.effectnebula.eide.ui.project.FileTabs
import io.github.effectnebula.eide.ui.project.FileTreePanel
import io.github.effectnebula.eide.ui.run.OutputPanel
import io.github.effectnebula.eide.ui.search.SearchBar
import io.github.effectnebula.eide.ui.theme.Eide
import io.github.effectnebula.eide.ui.theme.LocalEditorFont
import io.github.effectnebula.eide.ui.widgets.NoticeBar
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
    // Размер шрифта задаётся снаружи, потому что цифры для разных размеров
    // нужны сравнимые, а нажатие на кнопку в середине прогона сравнимости не даёт.
    val benchmarkFont = System.getProperty("eide.benchmarkFont")?.toFloatOrNull() ?: 13f
    val benchmarkHighlight = System.getProperty("eide.benchmarkHighlight") != "false"

    Window(
        onCloseRequest = ::exitApplication,
        title = "Effect IDE",
        state = WindowState(size = DpSize(1100.dp, 720.dp)),
    ) {
        // Самоснимок: -Deide.screenshot=/путь.png сохраняет окно и выходит.
        // Существует потому, что интерфейс пишется вслепую — без этого о нём
        // можно судить только по тому, что сборка не упала.
        Debug.screenshot?.let { path ->
            LaunchedEffect(Unit) {
                delay(SCREENSHOT_SETTLE_MS)
                captureScreen(File(path))
                exitProcess(0)
            }
        }

        CompositionLocalProvider(LocalEditorFont provides remember { jetBrainsMono() }) {
            if (benchmarkSeconds != null) {
                BenchmarkOnly(benchmarkSeconds, benchmarkFont, benchmarkHighlight)
            } else {
                DesktopShell()
            }
        }
    }
}

@Composable
private fun BenchmarkOnly(seconds: Int, fontSizeSp: Float, highlight: Boolean) {
    val editor = remember { benchmarkEditor(JdkGraphemeBreaker()) }
    RenderBenchmark(
        state = editor,
        initialFontSizeSp = fontSizeSp,
        initialHighlight = highlight,
        reporter = { report ->
            println(
                "секунда=%d шрифт=%.0f подсветка=%s fps=%.1f худший=%.1fмс просадок=%d/%d разметка=%d/%d".format(
                    report.second, report.fontSizeSp, if (report.highlighted) "да" else "нет",
                    report.fps, report.worstFrameMs,
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
    var showCanvas by remember { mutableStateOf(Debug.showCanvas) }

    // Взводится на Run и снимается первым же показом — как на Android и по той
    // же причине: иначе уйти с вкладки графики при работающей программе нельзя,
    // следующий кадр вернёт обратно.
    var awaitingFirstFrame by remember { mutableStateOf(false) }

    // Область кадров переживает несколько запусков: раннер одноразовый, а канва
    // — нет. Создаётся один раз на всё приложение.
    val canvas = remember { runCatching { DesktopCanvasArea.create() }.getOrNull() }
    DisposableEffect(canvas) { onDispose { canvas?.close() } }

    val startup = remember { startWorkspace() }
    val workspace = startup.workspace
    var notice by remember { mutableStateOf(startup.failure) }
    var revision by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION")
    revision

    val active = workspace.active

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

    Column(Modifier.fillMaxSize().background(Eide.colors.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Eide.colors.border)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab("редактор", !showBenchmark && !showCanvas) {
                showBenchmark = false
                showCanvas = false
            }
            if (canvas != null) {
                Tab("графика", showCanvas) { showCanvas = true }
            }
            Tab("отрисовка · P2", showBenchmark) {
                showBenchmark = true
                showCanvas = false
            }
        }

        if (showBenchmark) {
            val editor = remember { benchmarkEditor(JdkGraphemeBreaker()) }
            RenderBenchmark(editor, Modifier.weight(1f))
            return@Column
        }

        if (showCanvas && canvas != null) {
            CanvasView(canvas, Modifier.weight(1f))
            return@Column
        }

        Row(Modifier.weight(1f)) {
            Box(Modifier.width(TREE_WIDTH)) {
                FileTreePanel(
                    tree = workspace.tree,
                    selected = active?.file,
                    onOpen = { file ->
                        workspace.saveModified()
                        // Файл мог исчезнуть между тем, как дерево его показало,
                        // и тем, как по нему щёлкнули. Исключение отсюда уходит
                        // в обработчик события Compose и роняет приложение.
                        notice = runCatching { workspace.open(file) }
                            .fold({ null }, { "не открылся ${file.name}: ${it.message}" })
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

                notice?.let { NoticeBar(it) }

                RunPanel(
                    workspace = workspace,
                    active = active,
                    canvas = canvas,
                    onRunStarted = { awaitingFirstFrame = true },
                    onChanged = { revision++ },
                )
            }
        }
    }
}

/** Что получилось поднять на старте: проект и, если не вышло, причина. */
private class Startup(val workspace: Workspace, val failure: String?)

/**
 * Поднимает проект и открывает файл из `-Deide.open`, если он задан.
 *
 * Исключение отсюда стоило бы дорого: `remember` пересчитывается на каждой
 * попытке композиции, а композиция после броска повторяется — приложение
 * зависает молча, без окна и без сообщения. Диагностировать это по симптому
 * «gradle run не завершается» — час работы, так что причина ловится здесь.
 */
private fun startWorkspace(): Startup {
    // -Deide.project=/путь открывает чужую папку; по умолчанию — та, из
    // которой запущено приложение.
    val root = File(Debug.project ?: System.getProperty("user.dir"))
    val workspace = Workspace(ProjectTree(root), JdkGraphemeBreaker())

    // -Deide.open=путь открывает файл на старте. Нужно для самоснимка:
    // иначе увидеть редактор с вкладками можно только руками.
    val argument = Debug.open ?: return Startup(workspace, null)
    val target = openTarget(root, argument)

    return runCatching { workspace.open(target) }.fold(
        { Startup(workspace, null) },
        { Startup(workspace, "не открылся ${target.path}: ${it.message}") },
    )
}

/**
 * Куда показывает `-Deide.open`.
 *
 * Абсолютный путь остаётся собой: `File(root, absolute)` в Java склеивает их
 * в бессмыслицу вроде `/проект/tmp/файл.py`, и файл «не находится» по пути,
 * который в командной строке написан верно.
 */
internal fun openTarget(root: File, argument: String): File {
    val given = File(argument)
    return if (given.isAbsolute) given else File(root, argument)
}

/**
 * Редактор с запуском и панелью вывода.
 *
 * На десктопе интерпретатор — обычная программа, поэтому граница процессов уже
 * есть и городить свой раннер незачем. Обещания при этом те же, что на телефоне:
 * Stop останавливает всегда, зависшую программу снимает сторож.
 */
@Composable
private fun RunPanel(
    workspace: Workspace,
    active: io.github.effectnebula.eide.core.project.OpenFile?,
    canvas: DesktopCanvasArea?,
    onRunStarted: () -> Unit,
    onChanged: () -> Unit,
) {
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("готов") }
    var handle by remember { mutableStateOf<RunHandle?>(null) }
    val scope = rememberCoroutineScope()
    var autoRun by remember { mutableStateOf(Debug.autoRun) }

    // Сессия поиска живёт вместе с файлом: закрыли файл — забыли запрос.
    val search = active?.let { file ->
        remember(file) {
            SearchSession(file.state).apply {
                Debug.search?.let { setQuery(io.github.effectnebula.eide.core.editor.SearchQuery(it)) }
            }
        }
    }
    var showSearch by remember(active) { mutableStateOf(Debug.showSearch) }

    fun onMain(action: () -> Unit) {
        // Слушатель зовут из фоновых потоков бэкенда. Складывать строки вывода
        // из нескольких потоков без переноса в один — верный способ потерять
        // часть текста.
        scope.launch { action() }
    }

    fun run() {
        val target = active ?: return
        workspace.saveModified()
        onChanged()
        onRunStarted()

        output = ""
        status = "запускаю…"
        val startedAt = System.currentTimeMillis()

        handle = LocalPythonBackend().run(
            RunSpec(
                script = target.file,
                workDir = workspace.tree.root,
                limits = RunLimits(timeoutMillis = 30_000, maxResidentBytes = 512L * 1024 * 1024),
                // Шим и библиотеку программа находит через окружение: знать,
                // что означают эти переменные, бэкенду не нужно.
                environment = buildMap {
                    canvas?.let { putAll(it.environment(Debug.canvasLibrary)) }
                    Debug.shimDirectory?.let { put("PYTHONPATH", it) }
                },
            ),
            object : RunListener {
                override fun onStarted(pid: Int) = onMain { status = "работает, процесс $pid" }
                override fun onStdout(chunk: String) = onMain { output += chunk }
                override fun onStderr(chunk: String) = onMain { output += chunk }

                override fun onExit(code: Int) = onMain {
                    output += "\n[код $code за ${System.currentTimeMillis() - startedAt} мс]\n"
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
                    output += "\n[запустить не удалось: $error]\n"
                    status = "готов"
                    handle = null
                }
            },
        )
    }

    // Автозапуск для самопроверки: нажать Run самому я не могу, а увидеть, что
    // путь от кнопки до вывода работает, надо.
    if (autoRun && active != null) {
        LaunchedEffect(active) {
            autoRun = false
            run()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Eide.colors.panel)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val running = handle != null
            Tab(if (running) "Stop" else "Run", selected = true) {
                if (running) handle?.stop() else run()
            }
            if (search != null) {
                Tab("Найти", showSearch) { showSearch = !showSearch }
            }
            BasicText(status, style = TextStyle(color = Eide.colors.textDim, fontSize = 12.sp))
        }

        if (showSearch && search != null) {
            SearchBar(
                session = search,
                onClose = { showSearch = false },
                onChanged = onChanged,
            )
        }

        if (active != null) {
            EditorScreen(
                active.state,
                Modifier.weight(1f),
                search = search,
                highlighter = Highlighters.forFile(active.name),
            )
        } else {
            Box(Modifier.weight(1f).padding(16.dp)) {
                BasicText(
                    "выберите файл в дереве слева",
                    style = TextStyle(color = Eide.colors.textDim, fontSize = 13.sp),
                )
            }
        }

        OutputPanel(output, Modifier.fillMaxWidth().weight(OUTPUT_WEIGHT))
    }
}

/** Ширина колонки дерева: помещается путь средней длины, но не съедает редактор. */
private val TREE_WIDTH = 280.dp

/** Доля высоты под вывод: видно десяток строк, но редактор остаётся главным. */
private const val OUTPUT_WEIGHT = 0.35f

/**
 * Отладочные ключи десктопной сборки.
 *
 * Существуют потому, что интерфейс пишется без возможности его увидеть и
 * потрогать: снимок экрана и автозапуск — единственный способ проверить, что
 * путь от кнопки до вывода работает, а не только компилируется. В обычном
 * запуске ни один из них не задан, и приложение ведёт себя как обычно.
 */
private object Debug {
    /** `-Deide.screenshot=/путь.png` — снять экран и выйти. */
    val screenshot: String? get() = System.getProperty("eide.screenshot")

    /** `-Deide.project=/путь` — что считать проектом. */
    val project: String? get() = System.getProperty("eide.project")

    /** `-Deide.open=путь` — открыть файл на старте. */
    val open: String? get() = System.getProperty("eide.open")

    /** `-Deide.run` — запустить открытый файл сразу. */
    val autoRun: Boolean get() = System.getProperty("eide.run") != null

    /** `-Deide.canvas` — открыть вкладку графики сразу. */
    val showCanvas: Boolean get() = System.getProperty("eide.canvas") != null

    /** `-Deide.search=что` — открыть поиск с готовым запросом. */
    val search: String? get() = System.getProperty("eide.search")
    val showSearch: Boolean get() = search != null

    /**
     * Где лежит libeide_canvas.so и шим на Python.
     *
     * Пока задаются снаружи: в собранном дистрибутиве они поедут вместе с
     * приложением, и это отдельная работа по упаковке нативных библиотек под
     * три системы (риск R6 в плане). До неё графика на десктопе работает при
     * запуске из исходников.
     */
    val canvasLibrary: String? get() = System.getProperty("eide.canvasLib")
    val shimDirectory: String? get() = System.getProperty("eide.shimDir")
}

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
