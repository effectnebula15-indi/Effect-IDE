package io.github.effectnebula.eide.core.lsp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Обрамление сообщений LSP.
 *
 * Главное здесь — не «склеивается ли заголовок с телом», а поведение на чужом
 * потоке: сервер отдаёт байты как ему удобно, регистр заголовков не обещан,
 * а рассинхронизация не должна превращаться в OutOfMemory.
 */
class LspWireTest {

    /**
     * Поток, отдающий по одному байту за вызов.
     *
     * Настоящий сокет ведёт себя именно так, и любая реализация, ждущая «весь
     * заголовок за один read», на нём разваливается. Тот же приём, что в тестах
     * протокола раннера, и по той же причине.
     */
    private class DribbleStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0
        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= bytes.size) return -1
            buffer[offset] = bytes[index++]
            return 1
        }
    }

    /**
     * Сообщение с правильной длиной в заголовке.
     *
     * Длина считается по байтам — иначе тест повторяет ровно ту ошибку, которую
     * проверяет. Первая редакция этих тестов её и повторила: «метод» это пять
     * знаков и десять байт, и заголовок с пятёркой обрезал тело посередине.
     */
    private fun framed(body: String, name: String = "Content-Length", eol: String = "\r\n"): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return "$name: ${bytes.size}$eol$eol".toByteArray(Charsets.US_ASCII) + bytes
    }

    /** Сырое сообщение: длина в заголовке такая, как написано, верная или нет. */
    private fun raw(vararg parts: String) = parts.joinToString("").toByteArray(Charsets.UTF_8)

    @Test
    fun `a message survives the round trip`() {
        val out = ByteArrayOutputStream()
        LspWire.writeText(out, """{"jsonrpc":"2.0","id":1}""")

        assertEquals(
            """{"jsonrpc":"2.0","id":1}""",
            LspWire.readText(ByteArrayInputStream(out.toByteArray())),
        )
    }

    @Test
    fun `the length counts bytes, not characters`() {
        // «привет» — шесть знаков и двенадцать байт. Клиент, считающий знаки,
        // разъезжается на первом же русском слове в тексте документа.
        val out = ByteArrayOutputStream()
        LspWire.writeText(out, "привет")

        val written = out.toByteArray().toString(Charsets.US_ASCII)
        assertEquals("Content-Length: 12", written.substringBefore("\r\n"))
        assertEquals("привет", LspWire.readText(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `a stream that yields one byte at a time is still read correctly`() {
        val bytes = framed("""{"метод":1}""")

        assertEquals("""{"метод":1}""", LspWire.readText(DribbleStream(bytes)))
    }

    @Test
    fun `two messages in a row do not stick together`() {
        // Ровно здесь ломаются клиенты на BufferedReader: он забирает начало
        // второго сообщения в свой буфер, и дальше поток читается как мусор.
        val out = ByteArrayOutputStream()
        LspWire.writeText(out, "первое")
        LspWire.writeText(out, "второе")

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals("первое", LspWire.readText(input))
        assertEquals("второе", LspWire.readText(input))
        assertNull(LspWire.readText(input), "после двух сообщений поток кончился")
    }

    @Test
    fun `the header name is read regardless of case`() {
        val bytes = framed("ок", name = "content-length")

        assertEquals("ок", LspWire.readText(DribbleStream(bytes)))
    }

    @Test
    fun `other headers are skipped`() {
        val body = "тест"
        val bytes = raw(
            "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n",
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n",
            "\r\n",
            body,
        )

        assertEquals(body, LspWire.readText(DribbleStream(bytes)))
    }

    @Test
    fun `a clean end of stream is not an error`() {
        assertNull(LspWire.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a stream cut inside the headers is an error`() {
        val bytes = raw("Content-Length: 10\r\n")

        assertFailsWith<EOFException> { LspWire.read(DribbleStream(bytes)) }
    }

    @Test
    fun `a stream cut inside the body is an error`() {
        val bytes = raw("Content-Length: 10\r\n\r\nкор")

        assertFailsWith<EOFException> { LspWire.read(DribbleStream(bytes)) }
    }

    @Test
    fun `a message without Content-Length is an error`() {
        val bytes = raw("Content-Type: что-то\r\n\r\nтело")

        assertFailsWith<LspProtocolException> { LspWire.read(DribbleStream(bytes)) }
    }

    @Test
    fun `a length that is not a number is an error`() {
        val bytes = raw("Content-Length: много\r\n\r\n")

        assertFailsWith<LspProtocolException> { LspWire.read(DribbleStream(bytes)) }
    }

    @Test
    fun `an absurd length is refused, not allocated`() {
        // Рассинхронизация потока даёт выдуманное число, и `ByteArray(length)`
        // на нём — это OutOfMemory вместо внятной ошибки.
        val bytes = raw("Content-Length: ${Int.MAX_VALUE}\r\n\r\n")

        assertFailsWith<LspProtocolException> { LspWire.read(DribbleStream(bytes)) }
    }

    @Test
    fun `an empty body is a valid message`() {
        val bytes = raw("Content-Length: 0\r\n\r\n")

        assertEquals("", LspWire.readText(DribbleStream(bytes)))
    }

    @Test
    fun `a bare newline in the headers is forgiven`() {
        // Спецификация требует \r\n, но чужая реализация — это чужая реализация,
        // и падать из-за пропущенного \r дороже, чем его простить.
        val bytes = framed("ок", eol = "\n")

        assertEquals("ок", LspWire.readText(DribbleStream(bytes)))
    }
}
