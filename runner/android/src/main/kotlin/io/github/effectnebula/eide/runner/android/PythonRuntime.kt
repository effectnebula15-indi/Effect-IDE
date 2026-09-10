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

    /**
     * Параметры канвы передаются программе через окружение (`EIDE_CANVAS_*`).
     * Нулевой адрес означает «графики нет» — тогда `eide.available()` вернёт
     * ложь, и программа увидит это сама, а не упадёт на ровном месте.
     */
    @JvmStatic
    external fun nativeRun(
        home: String,
        script: String,
        workDir: String,
        outFd: Int,
        errFd: Int,
        canvasAddress: Long,
        canvasSize: Long,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Int
}
