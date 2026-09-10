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

    /**
     * Кладёт событие ввода в кольцо. Возвращает false, если оно переполнено.
     *
     * Координаты — уже канвы, не окна: пересчёт делает тот, кто знает, куда и
     * с каким масштабом вписан кадр.
     */
    fun postEvent(
        type: Int,
        pointer: Int,
        x: Int,
        y: Int,
        key: Int = 0,
        modifiers: Int = 0,
        timeMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val ring = inputOffset(width, height).toInt()
        val head = INT_HANDLE.get(buffer, ring + RING_HEAD_OFFSET) as Int
        val tail = INT_HANDLE.getAcquire(buffer, ring + RING_TAIL_OFFSET) as Int

        // Вычитание без знака: индексы растут без границ и переполняются.
        if ((head - tail).toUInt() >= EVENT_CAPACITY.toUInt()) {
            val dropped = SEQ_HANDLE.get(buffer, ring + RING_DROPPED_OFFSET) as Long
            SEQ_HANDLE.set(buffer, ring + RING_DROPPED_OFFSET, dropped + 1)
            return false
        }

        val slot = ring + RING_EVENTS_OFFSET + (head % EVENT_CAPACITY) * EVENT_BYTES
        buffer.putInt(slot, type)
        buffer.putInt(slot + 4, pointer)
        buffer.putInt(slot + 8, x)
        buffer.putInt(slot + 12, y)
        buffer.putInt(slot + 16, key)
        buffer.putInt(slot + 20, modifiers)
        buffer.putLong(slot + 24, timeMillis)

        // setRelease: событие записано целиком до того, как станет видно.
        INT_HANDLE.setRelease(buffer, ring + RING_HEAD_OFFSET, head + 1)
        return true
    }

    /** Сколько событий потеряно из-за переполнения кольца. */
    fun droppedEvents(): Long =
        SEQ_HANDLE.get(buffer, inputOffset(width, height).toInt() + RING_DROPPED_OFFSET) as Long

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
        private const val VERSION = 2
        private const val FORMAT_RGBA8888 = 1
        private const val SLOTS = 3
        private const val PIXELS_OFFSET = 256
        private const val BYTES_PER_PIXEL = 4

        private const val SLOT_STATE_OFFSET = 64
        private const val SLOT_STATE_STRIDE = 64
        private const val FRAME_FIELD_OFFSET = 8

        /*
         * Кольцо событий ввода: пишем сюда мы, читает программа пользователя.
         * Раскладка ec_input_ring из eide_canvas.h — head и tail разнесены по
         * кэш-линиям, чтобы запись одного не выбивала строку у другого.
         */
        private const val EVENT_CAPACITY = 256
        private const val EVENT_BYTES = 32
        private const val RING_HEAD_OFFSET = 0
        private const val RING_TAIL_OFFSET = 64
        private const val RING_DROPPED_OFFSET = 128
        private const val RING_EVENTS_OFFSET = 192
        private const val RING_BYTES = RING_EVENTS_OFFSET + EVENT_CAPACITY * EVENT_BYTES

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

        /**
         * Индексы кольца — четырёхбайтные, и им нужны те же барьеры.
         *
         * Событие обязано быть записано целиком до того, как читатель увидит
         * новое значение head; иначе программа прочитает половину события.
         */
        private val INT_HANDLE: VarHandle =
            MethodHandles.byteBufferViewVarHandle(IntArray::class.java, ByteOrder.nativeOrder())

        /** Логическое разрешение канвы: то же, что на Android (ADR-004). */
        const val DEFAULT_WIDTH = 480
        const val DEFAULT_HEIGHT = 800

        /** Смещение кольца: сразу за пикселями, выровнено на кэш-линию. */
        private fun inputOffset(width: Int, height: Int): Long {
            val afterPixels = PIXELS_OFFSET + SLOTS.toLong() * width * height * BYTES_PER_PIXEL
            return (afterPixels + 63L) and (63L).inv()
        }

        fun areaSize(width: Int, height: Int): Long = inputOffset(width, height) + RING_BYTES

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
            buffer.putInt(32, inputOffset(width, height).toInt())
            buffer.putInt(36, EVENT_CAPACITY)

            // Кольцо чистится явно: область переиспользуется между запусками,
            // и события прошлой программы новой доставаться не должны.
            val ring = inputOffset(width, height).toInt()
            for (i in 0 until RING_BYTES) buffer.put(ring + i, 0)

            // Магия пишется последней: до неё область считается непригодной.
            VarHandle.releaseFence()
            buffer.putInt(0, MAGIC)
        }
    }
}
