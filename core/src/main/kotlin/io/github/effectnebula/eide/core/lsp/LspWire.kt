package io.github.effectnebula.eide.core.lsp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Обрамление сообщений LSP: заголовки и тело.
 *
 * ```
 * Content-Length: 128\r\n
 * \r\n
 * {"jsonrpc":"2.0", ...}
 * ```
 *
 * Своё, а не готовая библиотека: весь протокол на этом уровне — одно число в
 * заголовке, а тянуть ради него зависимость в `:core`, которое обязано остаться
 * чистым Kotlin/JVM, несоразмерно (ADR-006 про то же самое, но с JGit).
 *
 * **Читается побайтно, а не через `BufferedReader`.** Буферизованный читатель
 * прочитал бы заголовки вместе с куском тела и оставил бы его у себя внутри —
 * дальше `read` вернул бы мусор. Это не теоретическая придирка: именно так
 * ломаются самодельные клиенты LSP, и ломаются не сразу, а когда сообщение
 * окажется длиннее буфера.
 *
 * **Длина — в байтах, а не в знаках.** Поэтому тело здесь `ByteArray`:
 * `"привет".length` это 6, а в UTF-8 оно занимает 12 байт, и такой клиент
 * разъезжается на первом же русском слове в тексте документа.
 */
object LspWire {

    /**
     * Потолок длины тела.
     *
     * Сервер вправе прислать что угодно, в том числе после рассинхронизации
     * потока, и `ByteArray(length)` на выдуманном числе — это OutOfMemory
     * вместо внятной ошибки. Шестнадцать мегабайт заведомо больше любого
     * настоящего сообщения: самое большое — это `didOpen` с текстом файла,
     * а файлы у нас ограничены десятью мегабайтами (ADR-005).
     */
    const val MAX_BODY_BYTES = 16 * 1024 * 1024

    private const val CONTENT_LENGTH = "content-length"

    fun write(out: OutputStream, body: ByteArray) {
        // Заголовок — только ASCII, поэтому кодировка здесь не важна; важно, что
        // число в нём считает байты тела, а не его знаки.
        out.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    fun writeText(out: OutputStream, body: String) = write(out, body.toByteArray(Charsets.UTF_8))

    /** Читает одно сообщение. Возвращает null, если поток корректно закончился. */
    fun read(input: InputStream): ByteArray? {
        var length = -1
        var sawAnyHeader = false

        while (true) {
            val line = readHeaderLine(input) ?: return if (sawAnyHeader) {
                throw EOFException("поток оборвался посреди заголовков")
            } else {
                null
            }

            if (line.isEmpty()) break
            sawAnyHeader = true

            val separator = line.indexOf(':')
            if (separator < 0) throw LspProtocolException("заголовок без двоеточия: «$line»")

            // Имя заголовка сравнивается без учёта регистра: так принято в HTTP,
            // откуда взято обрамление, и серверы этим пользуются.
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()

            if (name == CONTENT_LENGTH) {
                length = value.toIntOrNull()
                    ?: throw LspProtocolException("Content-Length не число: «$value»")
            }
            // Остальные заголовки (Content-Type) не нужны: кодировка в LSP всегда
            // UTF-8, а сам заголовок объявлен устаревшим.
        }

        if (length < 0) throw LspProtocolException("сообщение без Content-Length")
        if (length > MAX_BODY_BYTES) {
            throw LspProtocolException("Content-Length $length больше потолка $MAX_BODY_BYTES")
        }

        val body = ByteArray(length)
        if (length > 0 && !readFully(input, body)) {
            throw EOFException("поток оборвался посреди тела: ждали $length байт")
        }
        return body
    }

    fun readText(input: InputStream): String? = read(input)?.toString(Charsets.UTF_8)

    /**
     * Одна строка заголовка без завершающего `\r\n`.
     *
     * Возвращает null, если поток кончился до первого знака. Одинокий `\n`
     * принимается: спецификация требует `\r\n`, но чужая реализация — это чужая
     * реализация, а падать из-за пропущенного `\r` дороже, чем его простить.
     */
    private fun readHeaderLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString("US-ASCII")
            if (byte == '\n'.code) return line.toString("US-ASCII").removeSuffix("\r")
            line.write(byte)

            if (line.size() > MAX_HEADER_LINE) {
                throw LspProtocolException("строка заголовка длиннее $MAX_HEADER_LINE байт")
            }
        }
    }

    /**
     * Читает ровно [buffer].size байт.
     *
     * Своя реализация, а не `InputStream.readNBytes`: он появился в Java 9, а на
     * Android доступен только с API 33 — при minSdk 27 это упало бы на устройстве,
     * а не на сборке.
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

    /** Заголовок длиннее этого — признак не заголовка, а рассинхронизации. */
    private const val MAX_HEADER_LINE = 8 * 1024
}

class LspProtocolException(message: String) : RuntimeException(message)
