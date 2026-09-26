package io.github.effectnebula.eide.runner

import io.github.effectnebula.eide.core.exec.ExecutionBackend
import io.github.effectnebula.eide.core.exec.KillReason
import io.github.effectnebula.eide.core.exec.RunHandle
import io.github.effectnebula.eide.core.exec.RunListener
import io.github.effectnebula.eide.core.exec.RunSpec
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Запуск Python дочерним процессом. Десктопная реализация.
 *
 * Проще, чем на Android, и это не случайность: там CPython встроен через JNI и
 * ради отдельного процесса пришлось поднимать сервис и протокол поверх пары
 * сокетов. Здесь интерпретатор — обычная программа, граница процессов уже есть,
 * и протокол не нужен: труба и есть протокол.
 *
 * Общего у реализаций ровно то, что видно снаружи: [ExecutionBackend]. Так и
 * задумано — разница между «встроенный» и «отдельная программа» не должна
 * протекать в интерфейс.
 */
class LocalPythonBackend(
    private val interpreter: String = defaultInterpreter(),
) : ExecutionBackend {

    override fun run(spec: RunSpec, listener: RunListener): RunHandle {
        val process = try {
            ProcessBuilder(interpreter, "-u", spec.script.absolutePath)
                .directory(spec.workDir)
                .apply { environment().putAll(spec.environment) }
                .start()
        } catch (error: Throwable) {
            listener.onFailure(error)
            return RunHandle {}
        }

        val handle = LocalHandle(process, spec, listener)
        handle.start()
        return handle
    }

    private class LocalHandle(
        private val process: Process,
        private val spec: RunSpec,
        private val listener: RunListener,
    ) : RunHandle {

        private val killedFor = AtomicReference<KillReason?>(null)

        fun start() {
            listener.onStarted(process.pid().toInt())

            val pumps = listOf(
                pump(process.inputStream, "out", listener::onStdout),
                pump(process.errorStream, "err", listener::onStderr),
            )

            val limits = spec.limits
            if (limits.timeoutMillis != null || limits.maxResidentBytes != null) {
                thread("watchdog") { watch() }
            }

            thread("wait") {
                val code = runCatching { process.waitFor() }.getOrElse { -1 }
                // Ждём трубы: последние строки программы важнее скорости ответа.
                pumps.forEach { it.join(PUMP_JOIN_TIMEOUT_MS) }

                val reason = killedFor.get()
                if (reason != null) listener.onKilled(reason) else listener.onExit(code)
            }
        }

        override fun stop() = kill(KillReason.ByUser)

        private fun kill(reason: KillReason) {
            if (!killedFor.compareAndSet(null, reason)) return
            // destroyForcibly, а не destroy: программа в бесконечном цикле
            // сигнал завершения не обработает, а мы обещали, что «стоп» работает.
            process.destroyForcibly()
        }

        private fun watch() {
            val deadline = spec.limits.timeoutMillis?.let { System.currentTimeMillis() + it }
            val cap = spec.limits.maxResidentBytes

            while (process.isAlive && killedFor.get() == null) {
                if (deadline != null && System.currentTimeMillis() > deadline) {
                    kill(KillReason.Timeout)
                    return
                }
                if (cap != null && ProcessStats.isSupported) {
                    val resident = ProcessStats.residentBytes(process.pid())
                    if (resident != null && resident > cap) {
                        kill(KillReason.Memory)
                        return
                    }
                }
                try {
                    Thread.sleep(spec.limits.pollIntervalMillis)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        private fun pump(stream: InputStream, name: String, sink: (String) -> Unit): Thread =
            thread("pump-$name") {
                stream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(8 * 1024)
                    while (true) {
                        val read = runCatching { reader.read(buffer) }.getOrDefault(-1)
                        if (read <= 0) break
                        sink(String(buffer, 0, read))
                    }
                }
            }

        private fun thread(name: String, body: () -> Unit): Thread =
            Thread(body, "eide-$name").apply {
                isDaemon = true
                start()
            }
    }

    companion object {
        private const val PUMP_JOIN_TIMEOUT_MS = 2_000L

        /**
         * Чем запускать. Переопределяется через `eide.python` — на машине без
         * `python3` в PATH иначе не проверить ничего.
         */
        fun defaultInterpreter(): String = System.getProperty("eide.python") ?: "python3"
    }
}

/** Ручка, которой нечего останавливать: запуск не состоялся. */
private fun RunHandle(stop: () -> Unit): RunHandle = object : RunHandle {
    override fun stop() = stop()
}
