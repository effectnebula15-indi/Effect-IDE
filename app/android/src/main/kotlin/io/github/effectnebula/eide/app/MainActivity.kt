package io.github.effectnebula.eide.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.io.File

/**
 * Прототип P1: доказать, что официальный CPython запускается в отдельном процессе
 * и его вывод доходит до UI по нашему протоколу.
 *
 * Это не редактор. Ввод здесь на BasicTextField намеренно — своё ядро отрисовки
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

private const val SAMPLE = """import sys, platform

print("Python", sys.version.split()[0], "на", platform.machine())
for i in range(5):
    print("шаг", i)
print("готово")
"""

private val mainHandler = Handler(Looper.getMainLooper())

@Composable
private fun PrototypeScreen() {
    val context = LocalContext.current
    var source by remember { mutableStateOf(SAMPLE) }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    fun append(text: String) {
        output += text
    }

    fun run() {
        running = true
        output = ""

        val projectDir = File(context.filesDir, "projects/demo").apply { mkdirs() }
        val script = File(projectDir, "main.py").apply { writeText(source) }

        AndroidPythonBackend(context).run(
            script = script,
            workDir = projectDir,
            listener = object : AndroidPythonBackend.Listener {
                override fun onStdout(chunk: String) = post { append(chunk) }
                override fun onStderr(chunk: String) = post { append(chunk) }
                override fun onExit(code: Int) = post {
                    append("\n[процесс завершился с кодом $code]\n")
                    running = false
                }
                override fun onFailure(error: Throwable) = post {
                    append("\n[сбой канала: $error]\n")
                    running = false
                }

                // Не Context.getMainExecutor: он появился в API 28, а minSdk у нас 27.
                private fun post(action: () -> Unit) {
                    mainHandler.post(action)
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
            BasicText(text = "Effect IDE · прототип P1", color = TextColor, size = 14)
            Box(
                Modifier
                    .background(if (running) Border else Accent)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(if (running) Modifier else Modifier.clickableOnce { run() })
            ) {
                BasicText(text = if (running) "выполняется…" else "Run", color = Color.White, size = 13)
            }
        }

        BasicTextField(
            value = source,
            onValueChange = { source = it },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
            textStyle = TextStyle(color = TextColor, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            cursorBrush = SolidColor(Accent),
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Panel)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            BasicText(
                text = output.ifEmpty { "вывод программы появится здесь" },
                color = if (output.isEmpty()) TextDim else TextColor,
                size = 13,
                mono = true,
            )
        }
    }
}

@Composable
private fun BasicText(text: String, color: Color, size: Int, mono: Boolean = false) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )
}

/** Клик без material: нам нужен только обработчик, а не тема с ripple. */
private fun Modifier.clickableOnce(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
