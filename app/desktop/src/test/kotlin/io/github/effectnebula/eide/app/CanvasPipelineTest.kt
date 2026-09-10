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
    fun `events posted by the IDE reach the program`() {
        // Стык двух языков и трёх описаний одной структуры: C, ctypes и Kotlin.
        // Ни один компилятор его не проверяет, поэтому проверяется он здесь —
        // настоящей программой на Python, читающей то, что положил Kotlin.
        DesktopCanvasArea.create(width, height).use { area ->
            assertTrue(area.postEvent(type = 1, pointer = 0, x = 12, y = 34), "событие не положилось")
            assertTrue(area.postEvent(type = 2, pointer = 0, x = 56, y = 78, key = 9, modifiers = 3))
            assertTrue(area.postEvent(type = 3, pointer = 1, x = 90, y = 11))

            val recorder = runProgram(
                area,
                """
                import eide

                canvas = eide.canvas()
                for event in canvas.events():
                    print(event.type, event.pointer, event.x, event.y, event.key, event.modifiers)
                print('готово')
                """.trimIndent(),
            )

            assertEquals(0, recorder.exitCode, "программа упала: ${recorder.err}")
            assertEquals(
                """
                1 0 12 34 0 0
                2 0 56 78 9 3
                3 1 90 11 0 0
                готово
                """.trimIndent() + "\n",
                recorder.out.toString(),
                "события приехали не те",
            )
        }
    }

    @Test
    fun `an odd canvas size keeps both sides agreeing where the ring is`() {
        // Кольцо лежит за пикселями, выровненное на кэш-линию. Выравнивание
        // считают обе стороны отдельно, и разойтись им ничего не мешает: при
        // 64×48 пиксели и так кончаются на границе линии, и ошибка невидима.
        // Здесь размер выбран так, что выравнивание что-то меняет.
        DesktopCanvasArea.create(width = 65, height = 49).use { area ->
            assertTrue(area.postEvent(type = 2, pointer = 0, x = 7, y = 8), "событие не положилось")

            val recorder = runProgram(
                area,
                """
                import eide

                canvas = eide.canvas()
                events = list(canvas.events())
                print(len(events), events[0].x, events[0].y)
                """.trimIndent(),
            )

            assertEquals(0, recorder.exitCode, "программа упала: ${recorder.err}")
            assertEquals("1 7 8\n", recorder.out.toString(), "кольцо нашлось не там")
        }
    }

    @Test
    fun `a full ring is reported to the IDE`() {
        DesktopCanvasArea.create(width, height).use { area ->
            // Ёмкость кольца знает только C; здесь важно лишь, что переполнение
            // не молчит, а сообщается — иначе потерянный ввод не отличить от
            // непришедшего.
            var posted = 0
            while (area.postEvent(type = 2, pointer = 0, x = posted, y = 0)) {
                posted++
                if (posted > 10_000) break
            }

            assertTrue(posted in 1..10_000, "кольцо не заполнилось: $posted")
            assertEquals(1L, area.droppedEvents(), "потеря не учтена")
        }
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
