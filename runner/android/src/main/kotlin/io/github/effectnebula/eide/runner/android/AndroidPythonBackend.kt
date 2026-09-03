package io.github.effectnebula.eide.runner.android

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import io.github.effectnebula.eide.core.exec.MessageType
import io.github.effectnebula.eide.core.exec.Wire
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Клиентская половина: живёт в процессе UI и говорит с раннером по протоколу.
 *
 * Канал — пара сокетов, созданная здесь и переданная в сервис через Intent.
 * Не abstract-namespace unix-сокет и не порт на loopback: и то, и другое на Android
 * видно другим приложениям, а пара дескрипторов через Binder — нет.
 */
class AndroidPythonBackend(private val context: Context) {

    interface Listener {
        fun onStdout(chunk: String)
        fun onStderr(chunk: String)
        fun onExit(code: Int)
        fun onFailure(error: Throwable)
    }

    fun run(script: File, workDir: File, listener: Listener): Handle {
        val pair = ParcelFileDescriptor.createSocketPair()
        val mine = pair[0]
        val theirs = pair[1]

        try {
            context.startService(
                Intent(context, PythonRunnerService::class.java)
                    .putExtra(PythonRunnerService.EXTRA_CHANNEL, theirs)
            )
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

        val thread = Thread({ receive(mine, listener) }, "eide-runner-client")
        thread.isDaemon = true
        thread.start()

        return Handle(mine, thread)
    }

    private fun receive(channel: ParcelFileDescriptor, listener: Listener) {
        try {
            FileInputStream(channel.fileDescriptor).use { input ->
                while (true) {
                    val message = Wire.read(input) ?: break
                    when (message.type) {
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
            // Канал закрылся без кадра Exit — раннер убит снаружи или упал.
            listener.onExit(EXIT_CHANNEL_CLOSED)
        } catch (t: Throwable) {
            listener.onFailure(t)
        } finally {
            runCatching { channel.close() }
        }
    }

    /** Ручка запущенной программы. Закрытие канала — сигнал раннеру, что мы ушли. */
    class Handle(private val channel: ParcelFileDescriptor, private val reader: Thread) {
        fun close() {
            runCatching { channel.close() }
            reader.interrupt()
        }
    }

    companion object {
        /** Раннер исчез, не сообщив код возврата: чаще всего его убила система. */
        const val EXIT_CHANNEL_CLOSED: Int = -2
    }
}
