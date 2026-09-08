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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.project.TextFiles
import io.github.effectnebula.eide.core.text.Document
import io.github.effectnebula.eide.platform.android.IcuGraphemeBreaker
import io.github.effectnebula.eide.runner.android.AndroidPythonBackend
import io.github.effectnebula.eide.runner.android.KillReason
import io.github.effectnebula.eide.runner.android.RunLimits
import io.github.effectnebula.eide.ui.EditorScreen
import io.github.effectnebula.eide.ui.RenderBenchmark
import io.github.effectnebula.eide.ui.benchmarkDocument
import io.github.effectnebula.eide.ui.editor.ExtraKeyRow
import io.github.effectnebula.eide.ui.editorColors
import java.io.File

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

private enum class Screen { Code, Render }

@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.Code) }

    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().background(Border).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab("код", screen == Screen.Code) { screen = Screen.Code }
            Tab("отрисовка · P2", screen == Screen.Render) { screen = Screen.Render }
        }

        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Code -> CodeScreen()
                Screen.Render -> {
                    // Документ строится один раз: пересборка на кадре испортила бы замер.
                    val document = remember { benchmarkDocument() }
                    RenderBenchmark(document)
                }
            }
        }
    }
}

/**
 * Редактор и вывод программы над одним и тем же файлом.
 *
 * Прототипы P1 и P4 живут здесь же: Run запускает то, что сейчас в редакторе,
 * Stop убивает процесс, сторожевые лимиты снимают зависшую программу.
 */
@Composable
private fun CodeScreen() {
    val context = LocalContext.current

    val projectDir = remember {
        File(context.filesDir, "projects/demo").apply { mkdirs() }
    }
    val scriptFile = remember {
        File(projectDir, "main.py").apply {
            // Первый запуск: кладём пример, чтобы было что запустить сразу.
            if (!exists()) writeText(SAMPLE_PROGRAM)
        }
    }
    val loaded = remember { TextFiles.load(scriptFile) }
    val editorState = remember {
        EditorState(Document(loaded.text), IcuGraphemeBreaker())
    }

    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("готов") }
    var handle by remember { mutableStateOf<AndroidPythonBackend.Handle?>(null) }

    fun onMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    fun run() {
        output = ""
        status = "сохраняю и запускаю…"
        // Сохраняем ровно то, что видно в редакторе, и в том же формате, в каком
        // файл был открыт: кодировка и переносы не должны меняться сами по себе.
        TextFiles.save(scriptFile, editorState.text, loaded.format)

        val startedAt = System.currentTimeMillis()
        handle = AndroidPythonBackend(context).run(
            script = scriptFile,
            workDir = projectDir,
            limits = PROTOTYPE_LIMITS,
            listener = object : AndroidPythonBackend.Listener {
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(Panel).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label("main.py", TextColor, 13)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = handle != null
                Button(if (running) "Stop" else "Run", if (running) Danger else Accent) {
                    if (running) handle?.stop() else run()
                }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            EditorScreen(editorState)
        }

        Row(
            Modifier.fillMaxWidth().background(Border).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label(status, TextDim, 11)
            Label("предел ${PROTOTYPE_LIMITS.timeoutMillis} мс", TextDim, 11)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.8f)
                .background(Panel)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Label(
                text = output.ifEmpty { "вывод программы появится здесь" },
                color = if (output.isEmpty()) TextDim else TextColor,
                size = 13,
                mono = true,
            )
        }

        // Самым нижним элементом: ряд должен быть вплотную к клавиатуре, иначе
        // до него не дотянуться большим пальцем, ради которого он и нужен.
        ExtraKeyRow(editorState, editorColors())
    }
}

private const val SAMPLE_PROGRAM = """import sys, platform

print("Python", sys.version.split()[0], "на", platform.machine())
for i in range(5):
    print("шаг", i)
print("готово")
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
