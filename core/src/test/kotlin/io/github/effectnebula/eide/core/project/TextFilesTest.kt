package io.github.effectnebula.eide.core.project

import io.github.effectnebula.eide.core.text.Rope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextFilesTest {

    // --- кодировки -------------------------------------------------------------

    @Test
    fun `plain utf 8 is read as is`() {
        val loaded = TextFiles.decode("привет\nмир\n".toByteArray(Charsets.UTF_8))

        assertEquals("привет\nмир\n", loaded.text.toString())
        assertEquals(Charsets.UTF_8, loaded.format.charset)
        assertEquals(false, loaded.format.hasBom)
        assertNull(loaded.readOnlyReason)
    }

    @Test
    fun `byte order mark stays out of text and survives writing`() {
        // BOM в тексте — это невидимый символ в начале файла, который потом
        // ломает шебанг, JSON-парсеры и сравнение строк.
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "текст\n".toByteArray(Charsets.UTF_8)

        val loaded = TextFiles.decode(bytes)
        assertEquals("текст\n", loaded.text.toString(), "BOM просочился в текст")
        assertTrue(loaded.format.hasBom)

        val written = TextFiles.encode(loaded.text, loaded.format)
        assertTrue(written.contentEquals(bytes), "BOM потерялся при записи")
    }

    @Test
    fun `utf 16 is detected by its byte order mark`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "тест\n".toByteArray(Charsets.UTF_16LE)

        val loaded = TextFiles.decode(bytes)
        assertEquals("тест\n", loaded.text.toString())
        assertEquals(Charsets.UTF_16LE, loaded.format.charset)
    }

    @Test
    fun `undecodable file is not silently mangled`() {
        // Байты cp1251. Подставить символ замены значило бы: пользователь
        // сохранит файл и потеряет данные, ничего не заметив.
        val cp1251 = byteArrayOf(0xEF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte())

        val loaded = TextFiles.decode(cp1251)

        assertEquals(ReadOnlyReason.UnknownEncoding, loaded.readOnlyReason)
        assertTrue(loaded.isReadOnly)
    }

    @Test
    fun `utf 16 is not mistaken for a binary file`() {
        // Регрессия: в UTF-16 нулевые байты это норма — старший байт латиницы и
        // переноса строки как раз нулевой. Наивная проверка на нули объявляла
        // двоичным любой текст в этой кодировке.
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "line one\nline two\n".toByteArray(Charsets.UTF_16BE)

        val loaded = TextFiles.decode(bytes)

        assertNull(loaded.readOnlyReason, "текст в UTF-16 принят за двоичный файл")
        assertEquals("line one\nline two\n", loaded.text.toString())
    }

    @Test
    fun `binary file is detected by null bytes`() {
        val bytes = "начало".toByteArray() + byteArrayOf(0, 1, 2) + "конец".toByteArray()

        assertEquals(ReadOnlyReason.Binary, TextFiles.decode(bytes).readOnlyReason)
    }

    // --- переносы строк --------------------------------------------------------

    @Test
    fun `windows line endings are normalised inside the editor`() {
        val loaded = TextFiles.decode("одна\r\nдве\r\n".toByteArray())

        assertEquals("одна\nдве\n", loaded.text.toString(), "\\r просочился в текст")
        assertEquals(LineEnding.CrLf, loaded.format.lineEnding)
        assertEquals(3, loaded.text.lineCount)
    }

    @Test
    fun `windows line endings are restored on write`() {
        val loaded = TextFiles.decode("одна\r\nдве\r\n".toByteArray())
        val written = String(TextFiles.encode(loaded.text, loaded.format))

        assertEquals("одна\r\nдве\r\n", written, "файл сохранился с чужими переносами")
    }

    @Test
    fun `classic mac line endings are recognised`() {
        val loaded = TextFiles.decode("одна\rдве\r".toByteArray())

        assertEquals("одна\nдве\n", loaded.text.toString())
        assertEquals(LineEnding.Cr, loaded.format.lineEnding)
    }

    @Test
    fun `first line ending wins in a mixed file`() {
        val loaded = TextFiles.decode("одна\r\nдве\nтри\n".toByteArray())

        assertEquals(LineEnding.CrLf, loaded.format.lineEnding)
        assertEquals("одна\nдве\nтри\n", loaded.text.toString(), "смешанные переносы не свелись к одному")
    }

    // --- перенос в конце файла -------------------------------------------------

    @Test
    fun `file without trailing newline keeps it that way`() {
        // Дописать перенос — значит получить дифф на файл, который не трогали.
        val loaded = TextFiles.decode("без переноса".toByteArray())
        assertEquals(false, loaded.format.endsWithNewline)

        val written = String(TextFiles.encode(loaded.text, loaded.format))
        assertEquals("без переноса", written)
    }

    @Test
    fun `file with trailing newline keeps exactly one`() {
        val loaded = TextFiles.decode("строка\n".toByteArray())
        assertTrue(loaded.format.endsWithNewline)

        // Пользователь стёр перенос — при записи он вернётся, потому что таков формат файла.
        val written = String(TextFiles.encode(Rope.of("строка"), loaded.format))
        assertEquals("строка\n", written)
    }

    // --- размер ----------------------------------------------------------------

    @Test
    fun `oversized file opens read only but opens`() {
        val loaded = TextFiles.decode("текст\n".toByteArray(), tooLarge = true)

        assertEquals(ReadOnlyReason.TooLarge, loaded.readOnlyReason)
        assertEquals("текст\n", loaded.text.toString(), "большой файл должен читаться, а не пропадать")
    }

    // --- полный оборот через диск ---------------------------------------------

    @Test
    fun `round trip through the file system keeps bytes`() {
        val cases = mapOf(
            "unix" to "первая\nвторая\n",
            "windows" to "первая\r\nвторая\r\n",
            "без переноса в конце" to "одна строка",
            "пустой" to "",
        )

        for ((name, content) in cases) {
            val file = File.createTempFile("eide-", ".txt").apply { deleteOnExit() }
            file.writeBytes(content.toByteArray(Charsets.UTF_8))

            val loaded = TextFiles.load(file)
            TextFiles.save(file, loaded.text, loaded.format)

            assertEquals(content, file.readText(Charsets.UTF_8), "случай «$name» изменился при обороте")
        }
    }
}
