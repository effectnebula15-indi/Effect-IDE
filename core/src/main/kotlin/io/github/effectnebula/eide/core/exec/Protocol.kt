package io.github.effectnebula.eide.core.exec

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Формат сообщений между IDE и процессом-раннером (Шаг 3 плана).
 *
 *     [u32 длина payload, big-endian][u8 тип][payload]
 *
 * Один и тот же формат идёт и по локальному AF_UNIX-сокету, и по WebSocket на удалённый
 * бэкенд — меняется транспорт, не протокол.
 *
 * Кадры графики здесь НЕ передаются: локально они лежат в разделяемой памяти, а по
 * сокету идёт только уведомление [MessageType.FrameReady]. Отсюда скромный [MAX_PAYLOAD]:
 * если сообщение больше мегабайта, значит кто-то перепутал канал.
 */
enum class MessageType(val code: Int) {
    /** IDE → раннер: Start / Stop / Resize / Ping, тело — JSON. */
    Control(0x01),
    /** Раннер → IDE: сырые байты вывода программы. */
    Stdout(0x02),
    /** Раннер → IDE: сырые байты ошибок программы. */
    Stderr(0x03),
    /** IDE → раннер: сырые байты ввода. */
    Stdin(0x04),
    /** Раннер → IDE: кадр готов, тело — JSON с индексом буфера и порядковым номером. */
    FrameReady(0x05),
    /** Раннер → IDE: программа завершилась, тело — JSON с кодом возврата. */
    Exit(0x06),

    /**
     * Раннер → IDE: программа стартовала, тело — JSON с pid процесса.
     *
     * Без pid остановить зависший код нечем: раннер, крутящийся внутри
     * бесконечного цикла, не читает свой канал и о просьбе завершиться не узнает.
     * Единственное, что работает всегда, — убить процесс снаружи (ADR-002).
     */
    Started(0x07),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): MessageType? = byCode[code]
    }
}

class Message(val type: MessageType, val payload: ByteArray)

object Wire {
    const val HEADER_SIZE: Int = 5
    const val MAX_PAYLOAD: Int = 1 shl 20

    fun write(out: OutputStream, type: MessageType, payload: ByteArray) =
        write(out, type, payload, 0, payload.size)

    fun write(out: OutputStream, type: MessageType, payload: ByteArray, offset: Int, length: Int) {
        require(length in 0..MAX_PAYLOAD) { "payload $length байт, предел $MAX_PAYLOAD" }
        require(offset >= 0 && offset + length <= payload.size) { "срез вне массива" }

        val header = ByteArray(HEADER_SIZE)
        header[0] = (length ushr 24).toByte()
        header[1] = (length ushr 16).toByte()
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        header[4] = type.code.toByte()

        out.write(header)
        if (length > 0) out.write(payload, offset, length)
        out.flush()
    }

    /** Читает одно сообщение. Возвращает null, если поток корректно закончился. */
    fun read(input: InputStream): Message? {
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header)) return null

        val length = (header[0].toInt() and 0xFF shl 24) or
            (header[1].toInt() and 0xFF shl 16) or
            (header[2].toInt() and 0xFF shl 8) or
            (header[3].toInt() and 0xFF)
        val code = header[4].toInt() and 0xFF

        if (length < 0 || length > MAX_PAYLOAD) {
            throw ProtocolException("длина payload $length вне [0, $MAX_PAYLOAD] — поток рассинхронизирован")
        }
        val type = MessageType.fromCode(code)
            ?: throw ProtocolException("неизвестный тип сообщения 0x${code.toString(16)}")

        val payload = ByteArray(length)
        if (length > 0 && !readFully(input, payload)) {
            throw EOFException("поток оборвался посреди сообщения $type: ждали $length байт")
        }
        return Message(type, payload)
    }

    /**
     * Читает ровно [buffer].size байт.
     *
     * Своя реализация, а не `InputStream.readNBytes`: он появился в Java 9, а на Android
     * доступен только с API 33 — при minSdk 27 это упало бы на устройстве, а не на сборке.
     */
    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("поток оборвался: прочитано $read из ${buffer.size} байт")
            }
            read += n
        }
        return true
    }
}

class ProtocolException(message: String) : RuntimeException(message)
