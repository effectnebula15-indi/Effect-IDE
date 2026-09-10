package io.github.effectnebula.eide.runner

import io.github.effectnebula.eide.core.exec.KillReason
import io.github.effectnebula.eide.core.exec.RunLimits
import io.github.effectnebula.eide.core.exec.RunListener
import io.github.effectnebula.eide.core.exec.RunSpec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Запуск настоящего Python настоящим процессом.
 *
 * Единственное место во всём проекте, где путь исполнения проверяется целиком,
 * а не по частям: на Android то же самое можно только на телефоне. Отсюда и
 * ценность десктопной реализации — она не «ещё одна платформа», а способ
 * убедиться, что механизм вообще работает.
 */
class LocalPythonBackendTest {

    /** Результат прогона, собранный в одном месте. */
    private class Recorder : RunListener {
        val out = StringBuilder()
        val err = StringBuilder()
        var exitCode: Int? = null
        var killedFor: KillReason? = null
        var failure: Throwable? = null
        var pid: Int = -1

        /** Взводится на первом же куске вывода — по нему видно, когда он пришёл. */
        val firstOutput = CountDownLatch(1)

        /**
         * Всё пришедшее в порядке прихода, с пометкой откуда.
         *
         * Раздельные `out` и `err` про порядок между собой не говорят ничего,
         * а человек в панели вывода видит именно общий порядок.
         */
        val arrived = mutableListOf<Pair<String, String>>()

        private val done = CountDownLatch(1)

        override fun onStarted(pid: Int) {
            this.pid = pid
        }

        @Synchronized override fun onStdout(chunk: String) {
            out.append(chunk)
            arrived += "out" to chunk
            firstOutput.countDown()
        }

        @Synchronized override fun onStderr(chunk: String) {
            err.append(chunk)
            arrived += "err" to chunk
        }

        override fun onExit(code: Int) {
            exitCode = code
            done.countDown()
        }

        override fun onKilled(reason: KillReason) {
            killedFor = reason
            done.countDown()
        }

        override fun onFailure(error: Throwable) {
            failure = error
            done.countDown()
        }

        fun await(seconds: Long = 20): Boolean = done.await(seconds, TimeUnit.SECONDS)
    }

    private fun sandbox(): File = Files.createTempDirectory("eide-run").toFile().apply { deleteOnExit() }

    private fun script(text: String): Pair<File, File> {
        val dir = sandbox()
        return File(dir, "program.py").apply { writeText(text) } to dir
    }

    private fun run(
        text: String,
        limits: RunLimits = RunLimits(timeoutMillis = 10_000, maxResidentBytes = null),
        environment: Map<String, String> = emptyMap(),
    ): Recorder {
        val (file, dir) = script(text)
        val recorder = Recorder()
        LocalPythonBackend().run(RunSpec(file, dir, limits, environment), recorder)
        assertTrue(recorder.await(), "программа не завершилась за отведённое время")
        return recorder
    }

    @Test
    fun `output reaches the listener`() {
        val recorder = run("print('привет')")

        assertEquals(0, recorder.exitCode)
        assertEquals("привет\n", recorder.out.toString())
        assertTrue(recorder.pid > 0, "pid не сообщён")
    }

    @Test
    fun `stdout and stderr are separate`() {
        val recorder = run(
            """
            import sys
            print('в вывод')
            print('в ошибки', file=sys.stderr)
            """.trimIndent()
        )

        assertEquals("в вывод\n", recorder.out.toString())
        assertEquals("в ошибки\n", recorder.err.toString())
    }

    @Test
    fun `output keeps the order the program produced it in`() {
        // Человек в панели видит один поток текста. Трассировка, показанная
        // раньше строк, напечатанных до неё, — не мелочь: начинающий по такому
        // выводу решит, что программа упала в другом месте.
        //
        // Паузы по полсекунды нарочно: без них порядок между двумя трубами
        // не определён в принципе, и тест проверял бы удачу.
        val recorder = run(
            """
            import sys, time
            print('первая в вывод')
            time.sleep(0.5)
            print('вторая в ошибки', file=sys.stderr)
            time.sleep(0.5)
            print('третья в вывод')
            """.trimIndent()
        )

        val order = recorder.arrived.map { (source, chunk) -> source to chunk.trim() }
        assertEquals(
            listOf("out" to "первая в вывод", "err" to "вторая в ошибки", "out" to "третья в вывод"),
            order,
        )
    }

    @Test
    fun `exit code is passed through`() {
        val recorder = run("import sys; sys.exit(3)")

        assertEquals(3, recorder.exitCode)
    }

    @Test
    fun `a traceback comes as an error, not as a failure of the mechanism`() {
        // Ошибка в коде пользователя — это не поломка запуска. Путать эти два
        // случая значит показывать «IDE сломалась» на опечатку в скрипте.
        val recorder = run("raise ValueError('так и было задумано')")

        assertEquals(1, recorder.exitCode)
        assertTrue("ValueError" in recorder.err.toString())
        assertEquals(null, recorder.failure)
    }

    @Test
    fun `output arrives before the program ends`() {
        // Python по умолчанию буферизует вывод в трубу, и пока программа не
        // кончится, панель вывода остаётся пустой. Отсюда -u в командной строке.
        //
        // Проверять итоговый текст бесполезно: при выходе интерпретатор всё
        // равно сбросит буфер, и разницы не будет видно. Значение имеет момент,
        // когда первая строка дошла.
        val (file, dir) = script(
            """
            import time
            print('первая строка')
            time.sleep(3)
            print('вторая строка')
            """.trimIndent()
        )
        val recorder = Recorder()
        // PYTHONUNBUFFERED гасится намеренно: на машине, где он выставлен в
        // окружении, вывод не буферизуется и без нашего -u, и проверка не
        // проверяет ничего. Пустая строка для CPython означает «не задано».
        LocalPythonBackend().run(
            RunSpec(file, dir, environment = mapOf("PYTHONUNBUFFERED" to "")),
            recorder,
        )

        assertTrue(
            recorder.firstOutput.await(2, TimeUnit.SECONDS),
            "первая строка не дошла, пока программа работает — вывод буферизуется",
        )

        assertTrue(recorder.await(), "программа не завершилась")
        assertEquals("первая строка\nвторая строка\n", recorder.out.toString())
    }

    @Test
    fun `environment reaches the program`() {
        // Тем же путём приходят параметры канвы: бэкенду не нужно знать, что
        // означают эти переменные.
        //
        // Значение намеренно ASCII. Переменные окружения кодируются по
        // `sun.jnu.encoding`, а не по `file.encoding`: под локалью POSIX
        // кириллица в них превращается в «????». Наши значения — числа и
        // пути, так что ограничение не мешает; знать о нём надо.
        val recorder = run(
            "import os; print(os.environ['EIDE_TEST'])",
            environment = mapOf("EIDE_TEST" to "value-42"),
        )

        assertEquals("value-42\n", recorder.out.toString())
    }

    @Test
    fun `the working directory is the project folder`() {
        val recorder = run("import os; print(os.path.basename(os.getcwd()).startswith('eide-run'))")

        assertEquals("True\n", recorder.out.toString())
    }

    // --- остановка -------------------------------------------------------------

    @Test
    fun `an infinite loop is killed by the timeout`() {
        val started = System.currentTimeMillis()
        val recorder = run(
            "while True: pass",
            limits = RunLimits(timeoutMillis = 1_500, maxResidentBytes = null, pollIntervalMillis = 100),
        )

        assertEquals(KillReason.Timeout, recorder.killedFor)
        assertEquals(null, recorder.exitCode, "убитая программа не должна отчитаться кодом возврата")
        assertTrue(
            System.currentTimeMillis() - started < 10_000,
            "снятие по таймауту заняло слишком долго",
        )
    }

    @Test
    fun `stop kills a program that ignores the termination signal`() {
        // Просьбы завершиться недостаточно: программа вправе её перехватить и
        // проигнорировать, а мы обещали, что «стоп» работает всегда.
        val (file, dir) = script(
            """
            import signal, sys
            signal.signal(signal.SIGTERM, lambda *args: None)
            print('готова', flush=True)
            while True:
                pass
            """.trimIndent()
        )
        val recorder = Recorder()
        val handle = LocalPythonBackend().run(RunSpec(file, dir, RunLimits.NONE), recorder)

        assertTrue(recorder.firstOutput.await(10, TimeUnit.SECONDS), "программа не запустилась")
        handle.stop()

        assertTrue(recorder.await(10), "программа пережила остановку")
        assertEquals(KillReason.ByUser, recorder.killedFor)
    }

    @Test
    fun `stop kills a running program`() {
        val (file, dir) = script("while True: pass")
        val recorder = Recorder()
        val handle = LocalPythonBackend().run(RunSpec(file, dir, RunLimits.NONE), recorder)

        // Даём процессу действительно запуститься: убийство не начавшегося
        // процесса ничего не доказывает.
        Thread.sleep(500)
        handle.stop()

        assertTrue(recorder.await(), "программа не остановилась")
        assertEquals(KillReason.ByUser, recorder.killedFor)
    }

    @Test
    fun `a memory hog is killed by the limit`() {
        if (!ProcessStats.isSupported) return  // не Linux — предел памяти не действует

        val recorder = run(
            """
            buffer = []
            while True:
                buffer.append(bytearray(4 * 1024 * 1024))
            """.trimIndent(),
            limits = RunLimits(
                timeoutMillis = 15_000,
                maxResidentBytes = 200L * 1024 * 1024,
                pollIntervalMillis = 50,
            ),
        )

        assertEquals(KillReason.Memory, recorder.killedFor)
    }

    @Test
    fun `a missing interpreter is a failure of the mechanism, not of the program`() {
        val (file, dir) = script("print(1)")
        val recorder = Recorder()

        LocalPythonBackend(interpreter = "такого-интерпретатора-нет").run(RunSpec(file, dir), recorder)

        assertTrue(recorder.await(5))
        assertTrue(recorder.failure != null, "отсутствие интерпретатора должно быть сбоем механизма")
        assertEquals(null, recorder.exitCode)
    }
}
