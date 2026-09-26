package io.github.effectnebula.eide.runner.android

import io.github.effectnebula.eide.core.exec.MessageType
import io.github.effectnebula.eide.core.exec.Wire
import java.io.OutputStream

/**
 * Сериализует запись кадров в один канал.
 *
 * В канал пишут три потока сразу — stdout, stderr и поток, отправляющий код
 * возврата. Без синхронизации кадры перемешаются посреди заголовка, и приёмник
 * увидит мусор, который протокол честно распознает, но работать это перестанет.
 */
internal class FrameWriter(private val output: OutputStream) {

    @Synchronized
    fun write(type: MessageType, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        Wire.write(output, type, payload, offset, length)
    }
}
