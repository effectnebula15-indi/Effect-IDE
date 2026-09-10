package io.github.effectnebula.eide.runner.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import io.github.effectnebula.eide.core.exec.MessageType
import io.github.effectnebula.eide.core.exec.Wire
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import org.json.JSONObject

/**
 * Процесс исполнения кода пользователя (ADR-002).
 *
 * Объявлен в манифесте с `android:process=":runner"`, то есть живёт отдельно от UI.
 * Это единственный способ надёжно остановить бесконечный цикл и пережить падение
 * пользовательского кода, не потеряв несохранённый текст в редакторе.
 *
 * Процесс одноразовый: `Py_RunMain` финализирует интерпретатор, поэтому после
 * запуска мы убиваем сам процесс, и следующий запуск получает чистый.
 */
class PythonRunnerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val channel = intent?.getParcelableExtra<ParcelFileDescriptor>(EXTRA_CHANNEL)
        if (channel == null) {
            Log.e(TAG, "запуск без канала связи — нечего делать")
            stopSelf()
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val sharedCanvas = intent.getParcelableExtra<SharedMemory>(EXTRA_CANVAS)
        val canvas = sharedCanvas?.let {
            // Неудача подключения к канве не повод не запускать программу:
            // консольный код от неё не зависит, а eide.available() честно
            // скажет, что графики нет.
            runCatching {
                CanvasArea.attach(
                    it,
                    intent.getIntExtra(EXTRA_CANVAS_WIDTH, 0),
                    intent.getIntExtra(EXTRA_CANVAS_HEIGHT, 0),
                )
            }.onFailure { error -> Log.e(TAG, "канва недоступна", error) }.getOrNull()
        }

        Thread({ serve(channel, canvas) }, "eide-runner").start()
        return START_NOT_STICKY
    }

    private fun serve(channel: ParcelFileDescriptor, canvas: CanvasArea?) {
        val writer = FrameWriter(FileOutputStream(channel.fileDescriptor))
        var exitCode = -1
        try {
            val request = readStartRequest(FileInputStream(channel.fileDescriptor))
            // Отдаём свой pid сразу: пока программа не запущена, останавливать
            // нечего, а как только запустится — читать канал будет уже некому.
            writer.write(MessageType.Started, startedPayload())
            exitCode = runScript(request, writer, canvas)
        } catch (t: Throwable) {
            Log.e(TAG, "раннер упал", t)
            runCatching {
                writer.write(MessageType.Stderr, (t.toString() + "\n").toByteArray())
            }
        } finally {
            runCatching { writer.write(MessageType.Exit, exitPayload(exitCode)) }
            runCatching { channel.close() }
            // Процесс одноразовый — уходим целиком, а не ждём, пока система решит сама.
            Process.killProcess(Process.myPid())
        }
    }

    private fun readStartRequest(input: InputStream): StartRequest {
        val message = Wire.read(input) ?: error("канал закрылся до команды запуска")
        require(message.type == MessageType.Control) {
            "первым сообщением ожидали Control, пришло ${message.type}"
        }
        val json = JSONObject(String(message.payload, Charsets.UTF_8))
        return StartRequest(
            script = json.getString("script"),
            workDir = json.getString("workDir"),
        )
    }

    private fun runScript(request: StartRequest, writer: FrameWriter, canvas: CanvasArea?): Int {
        // Маркером распаковки служит время обновления пакета: любая пересборка
        // приложения — повод разложить stdlib заново.
        val stamp = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        val home = PythonAssets.ensureExtracted(this, stamp)

        val outPipe = ParcelFileDescriptor.createPipe()
        val errPipe = ParcelFileDescriptor.createPipe()

        val pumps = listOf(
            pump(outPipe[0], MessageType.Stdout, writer),
            pump(errPipe[0], MessageType.Stderr, writer),
        )

        val exitCode = try {
            PythonRuntime.nativeRun(
                home = home.absolutePath,
                script = request.script,
                workDir = request.workDir,
                outFd = outPipe[1].fd,
                errFd = errPipe[1].fd,
                canvasAddress = canvas?.address() ?: 0L,
                canvasSize = canvas?.let { CanvasArea.areaSize(it.width, it.height) } ?: 0L,
                canvasWidth = canvas?.width ?: 0,
                canvasHeight = canvas?.height ?: 0,
            )
        } finally {
            // Пока write-концы открыты, читающие потоки не увидят конца файла.
            runCatching { outPipe[1].close() }
            runCatching { errPipe[1].close() }
        }

        pumps.forEach { it.join(PUMP_JOIN_TIMEOUT_MS) }
        return exitCode
    }

    private fun pump(read: ParcelFileDescriptor, type: MessageType, writer: FrameWriter): Thread {
        val thread = Thread({
            FileInputStream(read.fileDescriptor).use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    writer.write(type, buffer, 0, n)
                }
            }
        }, "eide-$type")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun startedPayload(): ByteArray =
        JSONObject().put("pid", Process.myPid()).toString().toByteArray(Charsets.UTF_8)

    private fun exitPayload(code: Int): ByteArray =
        JSONObject().put("code", code).toString().toByteArray(Charsets.UTF_8)

    private data class StartRequest(val script: String, val workDir: String)

    companion object {
        private const val TAG = "eide.runner"
        private const val PUMP_JOIN_TIMEOUT_MS = 2_000L

        const val EXTRA_CHANNEL: String = "io.github.effectnebula.eide.runner.CHANNEL"

        /** Область кадров графики. `SharedMemory` — Parcelable, поэтому едет в Intent как есть. */
        const val EXTRA_CANVAS: String = "io.github.effectnebula.eide.runner.CANVAS"
        const val EXTRA_CANVAS_WIDTH: String = "io.github.effectnebula.eide.runner.CANVAS_W"
        const val EXTRA_CANVAS_HEIGHT: String = "io.github.effectnebula.eide.runner.CANVAS_H"
    }
}
