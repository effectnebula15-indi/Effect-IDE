package io.github.effectnebula.eide.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.runner.android.AndroidPythonBackend
import io.github.effectnebula.eide.runner.android.KillReason
import io.github.effectnebula.eide.runner.android.RunLimits
import java.io.File

/**
 * Прототипы P1 и P4 в одном экране.
 *
 * P1: официальный CPython запускается в отдельном процессе, и его вывод доходит
 * до UI по нашему протоколу.
 * P4: зависшую программу можно остановить, а сторожевые таймеры по времени и
 * памяти срабатывают сами.
 *
 * Это не редактор. Ввод на BasicTextField намеренно — своё ядро отрисовки
 * появится на этапе 1, и подменять его заглушкой раньше времени незачем.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PrototypeScreen() }
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

private class Sample(val title: String, val code: String)

private val SAMPLES = listOf(
    Sample(
        "привет",
        """
        import sys, platform

        print("Python", sys.version.split()[0], "на", platform.machine())
        for i in range(5):
            print("шаг", i)
        print("готово")
        """.trimIndent(),
    ),
    Sample(
        "вечный цикл",
        """
        print("вошёл в бесконечный цикл, останови меня")
        while True:
            pass
        """.trimIndent(),
    ),
    Sample(
        "ест память",
        """
        print("ем память мегабайтами")
        blocks = []
        while True:
            blocks.append(bytearray(1024 * 1024))
        """.trimIndent(),
    ),
    Sample(
        "ошибка",
        """
        print("сейчас будет исключение")
        1 / 0
        """.trimIndent(),
    ),
)

// Пределы для прототипа нарочно маленькие: ждать полминуты, чтобы убедиться,
// что сторож работает, — плохой способ проверять сторожа.
private val PROTOTYPE_LIMITS = RunLimits(
    timeoutMillis = 10_000,
    maxResidentBytes = 256L * 1024 * 1024,
)

@Composable
private fun PrototypeScreen() {
    val context = LocalContext.current
    var source by remember { mutableStateOf(SAMPLES.first().code) }
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("готов") }
    var handle by remember { mutableStateOf<AndroidPythonBackend.Handle?>(null) }

    fun onMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    fun run() {
        output = ""
        status = "запускаю…"

        val projectDir = File(context.filesDir, "projects/demo").apply { mkdirs() }
        val script = File(projectDir, "main.py").apply { writeText(source) }
        val startedAt = System.currentTimeMillis()

        handle = AndroidPythonBackend(context).run(
            script = script,
            workDir = projectDir,
            limits = PROTOTYPE_LIMITS,
            listener = object : AndroidPythonBackend.Listener {
                override fun onStarted(pid: Int) = onMain {
                    status = "работает, процесс $pid"
                }

                override fun onStdout(chunk: String) = onMain { output += chunk }

                override fun onStderr(chunk: String) = onMain { output += chunk }

                override fun onExit(code: Int) = onMain {
                    val ms = System.currentTimeMillis() - startedAt
                    output += "\n[завершилась с кодом $code за $ms мс]\n"
                    status = "готов"
                    handle = null
                }

                override fun onKilled(reason: KillReason) = onMain {
                    val ms = System.currentTimeMillis() - startedAt
                    val why = when (reason) {
                        KillReason.ByUser -> "остановлена вручную"
                        KillReason.Timeout -> "снята по таймауту"
                        KillReason.Memory -> "снята по пределу памяти"
                    }
                    output += "\n[$why через $ms мс]\n"
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

    Column(Modifier.fillMaxSize().background(Background)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(Panel).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label("Effect IDE · прототипы P1 и P4", TextColor, 14)

            val running = handle != null
            Box(
                Modifier
                    .background(if (running) Danger else Accent)
                    .clickable { if (running) handle?.stop() else run() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Label(if (running) "Stop" else "Run", Color.White, 13)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Panel)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (sample in SAMPLES) {
                Box(
                    Modifier
                        .background(Border)
                        .clickable { source = sample.code }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Label(sample.title, TextColor, 12)
                }
            }
        }

        BasicTextField(
            value = source,
            onValueChange = { source = it },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
            textStyle = TextStyle(color = TextColor, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            cursorBrush = SolidColor(Accent),
        )

        Row(
            Modifier.fillMaxWidth().background(Border).padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Label(
                "$status · предел ${PROTOTYPE_LIMITS.timeoutMillis} мс и " +
                    "${PROTOTYPE_LIMITS.maxResidentBytes?.div(1024 * 1024)} МБ",
                TextDim,
                11,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
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
    }
}

@Composable
private fun Label(text: String, color: Color, size: Int, mono: Boolean = false) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )
}
