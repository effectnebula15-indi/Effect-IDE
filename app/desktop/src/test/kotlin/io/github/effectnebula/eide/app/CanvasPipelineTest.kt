package io.github.effectnebula.eide.app

import io.github.effectnebula.eide.core.exec.KillReason
import io.github.effectnebula.eide.core.exec.RunLimits
import io.github.effectnebula.eide.core.exec.RunListener
import io.github.effectnebula.eide.core.exec.RunSpec
import io.github.effectnebula.eide.platform.desktop.DesktopCanvasArea
import io.github.effectnebula.eide.runner.LocalPythonBackend
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Графика целиком: программа на Python рисует, IDE читает кадры.
 *
 * Единственная автоматическая проверка всего конвейера — от шима на ctypes до
 * пикселей в буфере IDE. На Android то же самое можно только на телефоне и
 * только глазами; здесь это ловится сборкой.
 *
 * Заодно это единственное, что связывает две реализации чтения кадра: на C для
 * Android и на Kotlin здесь. Пишет всегда C, читает здесь Kotlin — расхождение
 * раскладки видно сразу.
 */
class CanvasPipelineTest {

    private val width = 64
    private val height = 48

    private class Recorder : RunListener {
        val out = StringBuilder()
        val err = StringBuilder()
        var exitCode: Int? = null
        var failure: Throwable? = null
        private val done = CountDownLatch(1)

        override fun onStarted(pid: Int) = Unit
        @Synchronized override fun onStdout(chunk: String) { out.append(chunk) }
        @Synchronized override fun onStderr(chunk: String) { err.append(chunk) }
        override fun onExit(code: Int) { exitCode = code; done.countDown() }
        override fun onKilled(reason: KillReason) { done.countDown() }
        override fun onFailure(error: Throwable) { failure = error; done.countDown() }

        fun await(seconds: Long = 30): Boolean = done.await(seconds, TimeUnit.SECONDS)
    }

    private fun canvasEnvironment(area: DesktopCanvasArea): Map<String, String> = mapOf(
        "EIDE_CANVAS_PATH" to area.path,
        "EIDE_CANVAS_SIZE" to area.areaSize.toString(),
        "EIDE_CANVAS_W" to area.width.toString(),
        "EIDE_CANVAS_H" to area.height.toString(),
        "EIDE_CANVAS_LIB" to System.getProperty("eide.canvasLib"),
        "PYTHONPATH" to System.getProperty("eide.shimDir"),
    )

    private fun runProgram(area: DesktopCanvasArea, source: String): Recorder {
        val dir = Files.createTempDirectory("eide-canvas-run").toFile().apply { deleteOnExit() }
        val script = File(dir, "program.py").apply { writeText(source) }

        val recorder = Recorder()
        LocalPythonBackend().run(
            RunSpec(
                script = script,
                workDir = dir,
                limits = RunLimits(timeoutMillis = 20_000, maxResidentBytes = null),
                environment = canvasEnvironment(area),
            ),
            recorder,
        )
        assertTrue(recorder.await(), "программа не завершилась")
        return recorder
    }

    @Test
    fun `a program draws and the frame reaches the reader`() {
        DesktopCanvasArea.create(width, height).use { area ->
            assertEquals(0L, area.latestFrame(), "кадры появились раньше программы")

            val recorder = runProgram(
                area,
                """
                import eide

                canvas = eide.canvas()
                canvas.clear(0x11223344)
                canvas.fill_rect(4, 20, 30, 6, 0xFF0000FF)
                canvas.present()
                print('нарисовано', canvas.width, canvas.height)
                """.trimIndent(),
            )

            assertEquals(0, recorder.exitCode, "программа упала: ${recorder.err}")
            assertEquals("нарисовано 64 48\n", recorder.out.toString())

            val pixels = IntArray(width * height)
            val frame = area.readFrame(pixels, since = 0)

            assertEquals(1L, frame, "кадр не дошёл до читателя")
            assertEquals(packed(0x11, 0x22, 0x33, 0x44), pixels[0], "фон не тот")
            assertEquals(packed(0xFF, 0x00, 0x00, 0xFF), pixels[22 * width + 10], "прямоугольник не там")
            assertEquals(packed(0x11, 0x22, 0x33, 0x44), pixels[10 * width + 31], "прямоугольник размазался")
        }
    }

    @Test
    fun `the reader sees the newest of many frames`() {
        DesktopCanvasArea.create(width, height).use { area ->
            val recorder = runProgram(
                area,
                """
                import eide

                canvas = eide.canvas()
                for step in range(1, 12):
                    canvas.clear(step * 0x01010101)
                    canvas.present()
                """.trimIndent(),
            )

            // Одиннадцать кадров, а не десять, намеренно: слотов три, и
            // одиннадцатый ложится не в нулевой. Ошибка в шаге между слотами
            // на круглом числе кадров остаётся незаметной.
            assertEquals(0, recorder.exitCode, "программа упала: ${recorder.err}")
            assertEquals(11L, area.latestFrame(), "потерялись кадры")

            val pixels = IntArray(width * height)
            assertEquals(11L, area.readFrame(pixels, since = 0))
            assertEquals(packed(0x0B, 0x0B, 0x0B, 0x0B), pixels[0])

            // Уже показанный кадр читать незачем.
            assertEquals(0L, area.readFrame(pixels, since = 11))
        }
    }

    @Test
    fun `a frame left unfinished is not shown`() {
        // Программу убивают штатно, кнопкой Stop, и она вправе умереть посреди
        // кадра. Показать половину нарисованного нельзя — на экране это выглядит
        // как мусор, а не как «программу остановили».
        DesktopCanvasArea.create(width, height).use { area ->
            val recorder = runProgram(
                area,
                """
                import eide

                canvas = eide.canvas()
                canvas.clear(0xAAAAAAFF)
                canvas.present()

                # Кадр открыт и не закрыт: ровно то, что остаётся после смерти
                # писателя посреди отрисовки.
                canvas.clear(0xBBBBBBFF)
                """.trimIndent(),
            )

            assertEquals(0, recorder.exitCode, "программа упала: ${recorder.err}")
            assertEquals(1L, area.latestFrame(), "показан незаконченный кадр")

            val pixels = IntArray(width * height)
            assertEquals(1L, area.readFrame(pixels, since = 0))
            assertEquals(packed(0xAA, 0xAA, 0xAA, 0xFF), pixels[0])
        }
    }

    @Test
    fun `a program without graphics says so instead of crashing`() {
        val dir = Files.createTempDirectory("eide-nocanvas").toFile().apply { deleteOnExit() }
        val script = File(dir, "program.py").apply {
            writeText("import eide; print('графика:', eide.available())")
        }

        val recorder = Recorder()
        LocalPythonBackend().run(
            RunSpec(
                script = script,
                workDir = dir,
                limits = RunLimits(timeoutMillis = 20_000, maxResidentBytes = null),
                environment = mapOf("PYTHONPATH" to System.getProperty("eide.shimDir")),
            ),
            recorder,
        )

        assertTrue(recorder.await())
        assertEquals(0, recorder.exitCode, "упало вместо честного ответа: ${recorder.err}")
        assertEquals("графика: False\n", recorder.out.toString())
    }

    /** Пиксель в памяти: R, G, B, A — как его собирает `ec_pack_rgba`. */
    private fun packed(r: Int, g: Int, b: Int, a: Int): Int =
        (a shl 24) or (b shl 16) or (g shl 8) or r
}
