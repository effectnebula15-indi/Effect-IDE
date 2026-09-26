package io.github.effectnebula.eide.runner.android

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.effectnebula.eide.core.exec.ExecutionBackend
import io.github.effectnebula.eide.core.exec.KillReason
import io.github.effectnebula.eide.core.exec.MessageType
import io.github.effectnebula.eide.core.exec.RunHandle
import io.github.effectnebula.eide.core.exec.RunLimits
import io.github.effectnebula.eide.core.exec.RunListener
import io.github.effectnebula.eide.core.exec.RunSpec
import io.github.effectnebula.eide.core.exec.Wire
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Клиентская половина: живёт в процессе UI и говорит с раннером по протоколу.
 *
 * Канал — пара сокетов из `ParcelFileDescriptor.createSocketPair()`, переданная
 * сервису через Intent. Не abstract-namespace unix-сокет и не порт на loopback:
 * и то, и другое на Android видно другим приложениям, а пара дескрипторов — нет.
 *
 * [canvas] — область кадров графики, если она есть. Задаётся при создании, а не
 * на каждый запуск: область переживает несколько запусков, а раннер одноразовый,
 * и это свойство бэкенда, а не конкретного запуска.
 */
class AndroidPythonBackend(
    private val context: Context,
    private val canvas: CanvasArea? = null,
) : ExecutionBackend {

    override fun run(spec: RunSpec, listener: RunListener): RunHandle {
        val script = spec.script
        val workDir = spec.workDir
        val limits = spec.limits
        val pair = ParcelFileDescriptor.createSocketPair()
        val mine = pair[0]
        val theirs = pair[1]

        try {
            val intent = Intent(context, PythonRunnerService::class.java)
                .putExtra(PythonRunnerService.EXTRA_CHANNEL, theirs)
            if (canvas != null) {
                intent
                    .putExtra(PythonRunnerService.EXTRA_CANVAS, canvas.shared)
                    .putExtra(PythonRunnerService.EXTRA_CANVAS_WIDTH, canvas.width)
                    .putExtra(PythonRunnerService.EXTRA_CANVAS_HEIGHT, canvas.height)
            }
            context.startService(intent)
        } finally {
            // Дескриптор продублирован в процесс раннера — наша копия больше не нужна.
            runCatching { theirs.close() }
        }

        val request = JSONObject()
            .put("script", script.absolutePath)
            .put("workDir", workDir.absolutePath)
            .toString()
            .toByteArray(Charsets.UTF_8)

        Wire.write(FileOutputStream(mine.fileDescriptor), MessageType.Control, request)

        val handle = Handle(mine, limits)
        val thread = Thread({ receive(mine, handle, listener) }, "eide-runner-client")
        thread.isDaemon = true
        thread.start()
        handle.attachReader(thread)

        return handle
    }

    private fun receive(channel: ParcelFileDescriptor, handle: Handle, listener: RunListener) {
        try {
            FileInputStream(channel.fileDescriptor).use { input ->
                while (true) {
                    val message = Wire.read(input) ?: break
                    when (message.type) {
                        MessageType.Started -> {
                            val pid = JSONObject(message.payload.toString(Charsets.UTF_8)).getInt("pid")
                            handle.onStarted(pid)
                            listener.onStarted(pid)
                        }
                        MessageType.Stdout -> listener.onStdout(message.payload.toString(Charsets.UTF_8))
                        MessageType.Stderr -> listener.onStderr(message.payload.toString(Charsets.UTF_8))
                        MessageType.Exit -> {
                            val code = JSONObject(message.payload.toString(Charsets.UTF_8)).getInt("code")
                            listener.onExit(code)
                            return
                        }
                        else -> Unit
                    }
                }
            }
            // Кадра Exit не было: либо раннер убит нами, либо его убила система.
            val reason = handle.killReason()
            if (reason != null) listener.onKilled(reason) else listener.onExit(EXIT_CHANNEL_CLOSED)
        } catch (t: Throwable) {
            val reason = handle.killReason()
            if (reason != null) listener.onKilled(reason) else listener.onFailure(t)
        } finally {
            handle.finish()
        }
    }

    /**
     * Ручка запущенной программы: остановка и надзор за лимитами.
     *
     * Остановка — именно убийство процесса, а не просьба завершиться. Программа,
     * крутящаяся в `while True`, свой канал не читает, а прервать её изнутри
     * интерпретатора надёжно нельзя (ADR-002).
     */
    class Handle internal constructor(
        private val channel: ParcelFileDescriptor,
        private val limits: RunLimits,
    ) : RunHandle {
        private val killedFor = AtomicReference<KillReason?>(null)
        @Volatile private var pid: Int = -1
        @Volatile private var reader: Thread? = null
        @Volatile private var watchdog: Thread? = null
        @Volatile private var finished = false

        internal fun attachReader(thread: Thread) {
            reader = thread
        }

        internal fun onStarted(runnerPid: Int) {
            pid = runnerPid
            if (limits.timeoutMillis != null || limits.maxResidentBytes != null) {
                watchdog = Thread({ watch(runnerPid) }, "eide-runner-watchdog").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        internal fun killReason(): KillReason? = killedFor.get()

        internal fun finish() {
            finished = true
            runCatching { channel.close() }
        }

        override fun stop() = kill(KillReason.ByUser)

        private fun kill(reason: KillReason) {
            if (!killedFor.compareAndSet(null, reason)) return
            val target = pid
            if (target > 0) {
                // Процесс того же приложения и того же UID — убить его мы вправе.
                Process.killProcess(target)
            }
            reader?.interrupt()
        }

        private fun watch(runnerPid: Int) {
            val deadline = limits.timeoutMillis?.let { System.currentTimeMillis() + it }
            while (!finished && killedFor.get() == null) {
                if (deadline != null && System.currentTimeMillis() > deadline) {
                    kill(KillReason.Timeout)
                    return
                }
                val cap = limits.maxResidentBytes
                if (cap != null) {
                    val resident = ProcessStats.residentBytes(runnerPid)
                        ?: return // процесса уже нет — сторожить нечего
                    if (resident > cap) {
                        kill(KillReason.Memory)
                        return
                    }
                }
                try {
                    Thread.sleep(limits.pollIntervalMillis)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    companion object {
        /** Раннер исчез, не сообщив код возврата, и мы его не убивали: постаралась система. */
        const val EXIT_CHANNEL_CLOSED: Int = -2
    }
}
