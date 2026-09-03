package io.github.effectnebula.eide.runner.android

/**
 * Мост к CPython. Живёт только в процессе раннера.
 *
 * [nativeRun] блокирует поток до конца работы программы пользователя и
 * финализирует интерпретатор: повторный запуск в том же процессе ненадёжен,
 * поэтому процесс после запуска одноразовый (ADR-002).
 */
internal object PythonRuntime {

    init {
        System.loadLibrary("eide_python")
    }

    @JvmStatic
    external fun nativeRun(
        home: String,
        script: String,
        workDir: String,
        outFd: Int,
        errFd: Int,
    ): Int
}
