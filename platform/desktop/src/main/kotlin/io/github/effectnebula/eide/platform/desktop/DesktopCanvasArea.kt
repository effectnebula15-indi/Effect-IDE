package io.github.effectnebula.eide.platform.desktop

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Область кадров графики на десктопе.
 *
 * Разделяемая память здесь — обычный файл, отображённый в память обоими
 * процессами: JVM читает, Python пишет. На Android для этого есть
 * `SharedMemory`, здесь достаточно `mmap`.
 *
 * Файл кладётся в `/dev/shm`, если он есть. Это tmpfs, то есть память: обычный
 * файл на диске означал бы обратную запись страниц шестьдесят раз в секунду —
 * полтора мегабайта на кадр, и всё это на флеш-память.
 *
 * **Здесь вторая реализация чтения кадра.** Первая — на C, в `native/canvas`,
 * и работает на Android. Раскладка описана в `docs/modules/native-canvas.md`, и
 * расхождение двух реализаций — настоящий риск. Смягчается тем, что эта
 * проверяется сквозным тестом против настоящего писателя на C, а та —
 * санитайзерами; обе против одного документа.
 */
class DesktopCanvasArea private constructor(
    private val file: File,
    private val channel: FileChannel,
    private val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
) : Closeable {

    /** Путь для программы пользователя: она отображает файл сама. */
    val path: String get() = file.absolutePath

    /**
     * Переменные окружения, по которым программа находит канву.
     *
     * Собираются здесь, а не в приложении: набор — часть контракта с шимом, и
     * знать его должна та же сторона, что знает раскладку.
     */
    fun environment(libraryPath: String? = null): Map<String, String> = buildMap {
        put("EIDE_CANVAS_PATH", path)
        put("EIDE_CANVAS_SIZE", areaSize.toString())
        put("EIDE_CANVAS_W", width.toString())
        put("EIDE_CANVAS_H", height.toString())
        if (libraryPath != null) put("EIDE_CANVAS_LIB", libraryPath)
    }

    val areaSize: Long get() = buffer.capacity().toLong()

    /** Байт на кадр. */
    val frameBytes: Int get() = width * height * BYTES_PER_PIXEL

    /**
     * Номер последнего целого кадра — без копирования.
     *
     * Ноль означает, что программа ещё ничего не нарисовала.
     */
    fun latestFrame(): Long {
        var newest = 0L
        for (slot in 0 until SLOTS) {
            val seq = readSeq(slot)
            if (seq == 0L || seq % 2L != 0L) continue

            val frame = readFrameNumber(slot)
            if (readSeq(slot) != seq) continue
            if (frame > newest) newest = frame
        }
        return newest
    }

    /**
     * Забирает последний целый кадр в [dst], если он новее [since].
     *
     * Возвращает номер скопированного кадра или 0, если нового нет.
     */
    fun readFrame(dst: IntArray, since: Long): Long {
        require(dst.size >= width * height) { "буфер меньше кадра" }
        return readFrame(since) { slot ->
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            view.position(PIXELS_OFFSET + slot * frameBytes)
            view.asIntBuffer().get(dst, 0, width * height)
        }
    }

    /**
     * То же самое байтами — так кадр отдают в текстуру.
     *
     * Отдельно от версии с `IntArray`, а не через преобразование: превращение
     * четырёхсот тысяч чисел в байты — ещё полтора мегабайта работы на кадр,
     * ровно то, чего вся эта схема избегает.
     */
    fun readFrame(dst: ByteArray, since: Long): Long {
        require(dst.size >= frameBytes) { "буфер меньше кадра" }
        return readFrame(since) { slot ->
            val view = buffer.duplicate()
            view.position(PIXELS_OFFSET + slot * frameBytes)
            view.get(dst, 0, frameBytes)
        }
    }

    private inline fun readFrame(since: Long, copy: (slot: Int) -> Unit): Long {
        repeat(READ_ATTEMPTS) {
            var chosen = -1
            var chosenFrame = since
            var chosenSeq = 0L

            for (slot in 0 until SLOTS) {
                val seq = readSeq(slot)
                if (seq == 0L || seq % 2L != 0L) continue

                val frame = readFrameNumber(slot)
                if (readSeq(slot) != seq) continue
                if (frame <= chosenFrame) continue

                chosen = slot
                chosenFrame = frame
                chosenSeq = seq
            }

            if (chosen < 0) return 0

            copy(chosen)

            // Счётчик не сдвинулся — значит писатель в этот слот не влезал.
            if (readSeq(chosen) == chosenSeq) return chosenFrame
        }
        return 0
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { file.delete() }
    }

    // --- чтение раскладки ------------------------------------------------------

    private fun readSeq(slot: Int): Long =
        SEQ_HANDLE.getAcquire(buffer, SLOT_STATE_OFFSET + slot * SLOT_STATE_STRIDE) as Long

    private fun readFrameNumber(slot: Int): Long =
        SEQ_HANDLE.getAcquire(
            buffer,
            SLOT_STATE_OFFSET + slot * SLOT_STATE_STRIDE + FRAME_FIELD_OFFSET,
        ) as Long

    companion object {
        /*
         * Раскладка обязана совпадать с native/canvas/include/eide_canvas.h.
         * Числа продублированы намеренно: тащить их из C в Kotlin нечем, кроме
         * генерации, а генерация ради восьми констант дороже, чем один тест,
         * который ловит расхождение.
         */
        private const val MAGIC = 0x45494443
        private const val VERSION = 1
        private const val FORMAT_RGBA8888 = 1
        private const val SLOTS = 3
        private const val PIXELS_OFFSET = 256
        private const val BYTES_PER_PIXEL = 4

        private const val SLOT_STATE_OFFSET = 64
        private const val SLOT_STATE_STRIDE = 64
        private const val FRAME_FIELD_OFFSET = 8

        private const val READ_ATTEMPTS = 4

        /**
         * Чтение счётчика с семантикой acquire.
         *
         * Обычный `ByteBuffer.getLong` барьера не ставит, и seqlock без него
         * перестаёт быть seqlock: процессор вправе прочитать пиксели раньше
         * счётчика. На x86 это не проявляется, на ARM — проявляется.
         */
        private val SEQ_HANDLE: VarHandle =
            MethodHandles.byteBufferViewVarHandle(LongArray::class.java, ByteOrder.nativeOrder())

        /** Логическое разрешение канвы: то же, что на Android (ADR-004). */
        const val DEFAULT_WIDTH = 480
        const val DEFAULT_HEIGHT = 800

        fun areaSize(width: Int, height: Int): Long =
            PIXELS_OFFSET + SLOTS.toLong() * width * height * BYTES_PER_PIXEL

        /** Создаёт и размечает область. */
        fun create(
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            directory: File = sharedMemoryDirectory(),
        ): DesktopCanvasArea {
            require(width > 0 && height > 0) { "размер канвы $width×$height" }

            val size = areaSize(width, height)
            val file = File.createTempFile("eide-canvas", ".bin", directory)
            file.deleteOnExit()

            val channel = RandomAccessFile(file, "rw").channel
            val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
                .order(ByteOrder.nativeOrder())

            writeHeader(buffer, width, height)
            return DesktopCanvasArea(file, channel, buffer, width, height)
        }

        /**
         * `/dev/shm` — это tmpfs, то есть память. Обычный файл на диске означал
         * бы обратную запись полутора мегабайт шестьдесят раз в секунду.
         */
        private fun sharedMemoryDirectory(): File {
            val shm = File("/dev/shm")
            return if (shm.isDirectory && shm.canWrite()) shm else File(System.getProperty("java.io.tmpdir"))
        }

        private fun writeHeader(buffer: ByteBuffer, width: Int, height: Int) {
            for (i in 0 until PIXELS_OFFSET) buffer.put(i, 0)

            buffer.putInt(4, VERSION)
            buffer.putInt(8, width)
            buffer.putInt(12, height)
            buffer.putInt(16, FORMAT_RGBA8888)
            buffer.putInt(20, SLOTS)
            buffer.putInt(24, width * height * BYTES_PER_PIXEL)
            buffer.putInt(28, PIXELS_OFFSET)

            // Магия пишется последней: до неё область считается непригодной.
            VarHandle.releaseFence()
            buffer.putInt(0, MAGIC)
        }
    }
}
