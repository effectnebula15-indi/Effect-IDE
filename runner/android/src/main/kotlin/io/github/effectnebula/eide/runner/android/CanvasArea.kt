package io.github.effectnebula.eide.runner.android

import android.graphics.Bitmap
import android.os.SharedMemory
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Область разделяемой памяти под кадры графики (ADR-004).
 *
 * Создаёт её процесс IDE, отдаёт раннеру через Intent (`SharedMemory` —
 * Parcelable), и дальше раннер рисует, а IDE читает. Раскладка и правила
 * доступа — в `native/canvas`, здесь только Android-обвязка.
 *
 * Защиты между процессами нет и не будет: они наши оба, а песочница в v1
 * существует ради предсказуемости, а не ради безопасности (ADR-003). Читатель
 * отображает область только на чтение — это ловит наши собственные ошибки, а не
 * чужой злой умысел.
 */
class CanvasArea private constructor(
    val shared: SharedMemory,
    private val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
) : Closeable {

    /**
     * Адрес области как число — его получает код на Python через переменную
     * окружения. Отображением занимается тот, кто умеет; библиотеке на C
     * остаётся арифметика.
     */
    fun address(): Long = nativeAddressOf(buffer)

    /**
     * Забирает последний целый кадр в [bitmap], если он новее [since].
     *
     * Возвращает номер скопированного кадра или 0, если нового нет. Ноль —
     * обычное дело, а не ошибка: программа могла ничего не нарисовать с
     * прошлого раза, и перезаливать текстуру незачем.
     *
     * [bitmap] обязан быть `ARGB_8888` того же размера, что и область.
     */
    fun readFrameInto(bitmap: Bitmap, since: Long): Long =
        nativeReadFrame(buffer, bitmap, since)

    /**
     * Номер последнего нарисованного кадра — без копирования.
     *
     * По нему видно, рисует ли программа вообще: `readFrameInto` ради того же
     * ответа скопировал бы полтора мегабайта.
     */
    fun latestFrame(): Long = nativeLatestFrame(buffer)

    /** Bitmap нужного формата и размера. */
    fun createBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    override fun close() {
        runCatching { SharedMemory.unmap(buffer) }
        runCatching { shared.close() }
    }

    companion object {
        init {
            System.loadLibrary("eide_canvas")
        }

        /**
         * Логическое разрешение канвы по умолчанию.
         *
         * Намеренно меньше экрана (ADR-004): копия кадра 1080×1920 — восемь
         * мегабайт, и при 60 кадрах в секунду это полгигабайта в секунду на
         * один только memcpy. 480×800 — полтора мегабайта, порядка 0.2 мс.
         */
        const val DEFAULT_WIDTH = 480
        const val DEFAULT_HEIGHT = 800

        /** Создаёт и размечает область. Вызывается в процессе IDE. */
        fun create(width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT): CanvasArea {
            require(width > 0 && height > 0) { "размер канвы $width×$height" }
            val size = nativeAreaSize(width, height)
            require(size in 1..Int.MAX_VALUE) { "область $size байт не отобразить" }

            val shared = SharedMemory.create("eide-canvas", size.toInt())
            // Разметка требует записи, дальнейшее чтение — нет. Поэтому
            // отображаем дважды: короткий раз на запись, потом насовсем на чтение.
            val writable = shared.mapReadWrite()
            val initialised = try {
                nativeInitArea(writable, width, height)
            } finally {
                SharedMemory.unmap(writable)
            }
            if (!initialised) {
                shared.close()
                error("не удалось разметить область канвы $width×$height")
            }

            return CanvasArea(shared, shared.mapReadOnly(), width, height)
        }

        /** Подключается к уже размеченной области. Вызывается в процессе раннера. */
        fun attach(shared: SharedMemory, width: Int, height: Int): CanvasArea =
            CanvasArea(shared, shared.mapReadWrite(), width, height)

        /** Размер области в байтах — его же получает Python, чтобы проверить границы. */
        fun areaSize(width: Int, height: Int): Long = nativeAreaSize(width, height)

        @JvmStatic private external fun nativeAreaSize(width: Int, height: Int): Long
        @JvmStatic private external fun nativeInitArea(area: ByteBuffer, width: Int, height: Int): Boolean
        @JvmStatic private external fun nativeAddressOf(area: ByteBuffer): Long
        @JvmStatic private external fun nativeLatestFrame(area: ByteBuffer): Long
        @JvmStatic private external fun nativeReadFrame(area: ByteBuffer, bitmap: Bitmap, since: Long): Long
    }
}
