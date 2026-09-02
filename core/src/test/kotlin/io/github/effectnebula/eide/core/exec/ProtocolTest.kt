package io.github.effectnebula.eide.core.exec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProtocolTest {

    @Test
    fun `сообщение переживает запись и чтение`() {
        val random = Random(42)
        val out = ByteArrayOutputStream()
        val sent = List(200) {
            val type = MessageType.entries[random.nextInt(MessageType.entries.size)]
            val payload = random.nextBytes(random.nextInt(0, 5000))
            Wire.write(out, type, payload)
            type to payload
        }

        val input = ByteArrayInputStream(out.toByteArray())
        for ((i, expected) in sent.withIndex()) {
            val message = Wire.read(input) ?: error("поток кончился на сообщении $i")
            assertEquals(expected.first, message.type, "тип сообщения $i")
            assertContentEquals(expected.second, message.payload, "payload сообщения $i")
        }
        assertNull(Wire.read(input), "после последнего сообщения поток должен корректно кончиться")
    }

    @Test
    fun `читает сообщение из потока, отдающего байты по одному`() {
        // Сокет вправе отдать сколько угодно байт за раз. Наивное чтение здесь ломается.
        val out = ByteArrayOutputStream()
        val payload = Random(7).nextBytes(3000)
        Wire.write(out, MessageType.Stdout, payload)

        val message = Wire.read(DribblingStream(out.toByteArray()))
        assertEquals(MessageType.Stdout, message?.type)
        assertContentEquals(payload, message?.payload)
    }

    @Test
    fun `обрыв посреди сообщения это ошибка, а не тишина`() {
        val out = ByteArrayOutputStream()
        Wire.write(out, MessageType.Stdout, ByteArray(100))
        val truncated = out.toByteArray().copyOf(Wire.HEADER_SIZE + 40)

        assertFailsWith<EOFException> { Wire.read(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun `мусор в заголовке распознаётся, а не превращается в гигабайтный буфер`() {
        val garbage = byteArrayOf(0x7F, -1, -1, -1, 0x01)
        assertFailsWith<ProtocolException> { Wire.read(ByteArrayInputStream(garbage)) }

        val unknownType = byteArrayOf(0, 0, 0, 0, 0x63)
        assertFailsWith<ProtocolException> { Wire.read(ByteArrayInputStream(unknownType)) }
    }

    @Test
    fun `слишком большой payload не отправляется`() {
        assertFailsWith<IllegalArgumentException> {
            Wire.write(ByteArrayOutputStream(), MessageType.Stdout, ByteArray(Wire.MAX_PAYLOAD + 1))
        }
    }

    /** Поток, отдающий по одному байту за вызов read — как это делает настоящий сокет. */
    private class DribblingStream(private val data: ByteArray) : InputStream() {
        private var position = 0
        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            b[off] = data[position++]
            return 1
        }
    }
}
