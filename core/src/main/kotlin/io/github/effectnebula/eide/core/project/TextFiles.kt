package io.github.effectnebula.eide.core.project

import io.github.effectnebula.eide.core.text.Rope
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/** Чем в файле кончаются строки. */
enum class LineEnding(val text: String) {
    Lf("\n"),
    CrLf("\r\n"),
    Cr("\r"),
}

/**
 * Всё, что нужно знать о файле, чтобы записать его обратно без сюрпризов.
 *
 * Редактор не вправе менять кодировку, метку порядка байтов или стиль переносов
 * только потому, что кто-то поправил одну строку: в чужом репозитории это
 * превратится в дифф на весь файл.
 */
data class TextFileFormat(
    val charset: Charset,
    val hasBom: Boolean,
    val lineEnding: LineEnding,
    val endsWithNewline: Boolean,
) {
    companion object {
        /** Формат для нового файла: UTF-8 без BOM, переносы `\n`, файл кончается переносом. */
        val Default: TextFileFormat = TextFileFormat(Charsets.UTF_8, false, LineEnding.Lf, true)
    }
}

/** Почему файл открыт только для чтения. */
enum class ReadOnlyReason {
    /** Слишком большой: редактировать можно, но подсветка и LSP на нём работать не будут. */
    TooLarge,

    /** Похож на бинарный: внутри нулевые байты. */
    Binary,

    /** Не разбирается ни как UTF-8, ни по метке порядка байтов. */
    UnknownEncoding,
}

/** Результат открытия файла. */
data class LoadedFile(
    val text: Rope,
    val format: TextFileFormat,
    val readOnlyReason: ReadOnlyReason? = null,
) {
    val isReadOnly: Boolean get() = readOnlyReason != null
}

/**
 * Чтение и запись текстовых файлов.
 *
 * Внутри редактора текст всегда с переносами `\n`: иначе каждая операция над
 * офсетами должна была бы знать про двухсимвольный `\r\n`, и рано или поздно
 * какая-нибудь бы не знала. Стиль переносов запоминается и возвращается при записи.
 */
object TextFiles {

    /**
     * Порог редактируемости. Выше него файл открывается только на чтение —
     * не из-за самого текста, а потому что подсветка и LSP на таком объёме
     * не работают (ADR-005).
     */
    const val MAX_EDITABLE_BYTES: Long = 10L * 1024 * 1024

    /** Сколько байт в начале смотреть на предмет бинарности. */
    private const val BINARY_PROBE_BYTES = 8_000

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val BOM_UTF16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    fun load(file: File, maxEditableBytes: Long = MAX_EDITABLE_BYTES): LoadedFile {
        val bytes = file.readBytes()
        return decode(bytes, tooLarge = file.length() > maxEditableBytes)
    }

    /** Отдельно от [load] — чтобы разбор можно было проверить тестами без файловой системы. */
    fun decode(bytes: ByteArray, tooLarge: Boolean = false): LoadedFile {
        val (charset, bomLength) = detectCharset(bytes)

        // Проверка на бинарность — только для UTF-8. В UTF-16 нулевые байты это
        // норма: старший байт латиницы и переноса строки как раз нулевой, и
        // наивная проверка объявила бы двоичным любой текст в этой кодировке.
        // Метка порядка байтов — достаточно надёжный признак, что это текст.
        val looksBinary = bomLength == 0 && isBinary(bytes, 0)
        if (looksBinary) {
            return LoadedFile(Rope.EMPTY, TextFileFormat.Default, ReadOnlyReason.Binary)
        }

        val decoded = strictDecode(bytes, bomLength, charset)
            ?: return LoadedFile(Rope.EMPTY, TextFileFormat.Default, ReadOnlyReason.UnknownEncoding)

        val lineEnding = detectLineEnding(decoded)
        val normalized = normalizeLineEndings(decoded)

        val format = TextFileFormat(
            charset = charset,
            hasBom = bomLength > 0,
            lineEnding = lineEnding,
            endsWithNewline = normalized.endsWith("\n"),
        )
        return LoadedFile(
            text = Rope.of(normalized),
            format = format,
            readOnlyReason = if (tooLarge) ReadOnlyReason.TooLarge else null,
        )
    }

    fun save(file: File, text: Rope, format: TextFileFormat) {
        file.writeBytes(encode(text, format))
    }

    fun encode(text: Rope, format: TextFileFormat): ByteArray {
        val body = buildString(text.length + 16) {
            text.forEachChunk { append(it) }
            if (format.endsWithNewline && (isEmpty() || last() != '\n')) append('\n')
            if (!format.endsWithNewline) {
                while (isNotEmpty() && last() == '\n') setLength(length - 1)
            }
        }
        val withEndings = if (format.lineEnding == LineEnding.Lf) {
            body
        } else {
            body.replace("\n", format.lineEnding.text)
        }

        val encoded = withEndings.toByteArray(format.charset)
        if (!format.hasBom) return encoded

        val bom = when (format.charset) {
            Charsets.UTF_8 -> BOM_UTF8
            Charsets.UTF_16BE -> BOM_UTF16_BE
            Charsets.UTF_16LE -> BOM_UTF16_LE
            else -> return encoded
        }
        return bom + encoded
    }

    private fun detectCharset(bytes: ByteArray): Pair<Charset, Int> = when {
        bytes.startsWith(BOM_UTF8) -> Charsets.UTF_8 to BOM_UTF8.size
        bytes.startsWith(BOM_UTF16_BE) -> Charsets.UTF_16BE to BOM_UTF16_BE.size
        bytes.startsWith(BOM_UTF16_LE) -> Charsets.UTF_16LE to BOM_UTF16_LE.size
        else -> Charsets.UTF_8 to 0
    }

    /**
     * Строгое декодирование: при первом же неверном байте возвращаем null.
     *
     * Молча подставлять символ замены нельзя — пользователь сохранит файл и
     * потеряет данные, ничего не заметив. Гадать между cp1251 и koi8-r мы тоже
     * не будем: ошибка угадывания портит текст так же тихо.
     */
    private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String? {
        val decoder: CharsetDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        return runCatching { decoder.decode(buffer).toString() }.getOrNull()
    }

    private fun isBinary(bytes: ByteArray, offset: Int): Boolean {
        val limit = minOf(bytes.size, offset + BINARY_PROBE_BYTES)
        for (i in offset until limit) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    /**
     * Стиль переносов определяется по первому встреченному.
     *
     * В смешанном файле побеждает первый: любой выбор здесь произволен, но
     * «первый» хотя бы предсказуем и не зависит от того, где пользователь правил.
     */
    private fun detectLineEnding(text: String): LineEnding {
        for (i in text.indices) {
            when (text[i]) {
                '\r' -> return if (i + 1 < text.length && text[i + 1] == '\n') LineEnding.CrLf else LineEnding.Cr
                '\n' -> return LineEnding.Lf
                else -> Unit
            }
        }
        return LineEnding.Lf
    }

    /**
     * Внутри редактора текст всегда только с `\n`.
     *
     * Смешанные переносы приводятся к одному виду тоже: иначе каждая операция над
     * офсетами должна была бы знать про двухсимвольный `\r\n`, и рано или поздно
     * какая-нибудь бы не знала.
     */
    private fun normalizeLineEndings(text: String): String =
        if (text.contains('\r')) text.replace("\r\n", "\n").replace('\r', '\n') else text

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
