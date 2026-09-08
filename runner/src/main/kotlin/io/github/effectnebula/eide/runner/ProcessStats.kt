package io.github.effectnebula.eide.runner

import java.io.File

/**
 * Резидентная память чужого процесса.
 *
 * Через `/proc/<pid>/status`, а не `statm`: там значение сразу в килобайтах, и
 * не нужно знать размер страницы. На машинах с 16-килобайтными страницами
 * подстановка привычных 4096 даёт четырёхкратную ошибку.
 *
 * **Только Linux.** На macOS и Windows `/proc` нет, и предел памяти там просто
 * не действует. Это лучше, чем убивать программу по выдуманной цифре, но об
 * этом надо знать: `isSupported` говорит правду.
 */
internal object ProcessStats {

    val isSupported: Boolean = File("/proc/self/status").exists()

    /** Резидентная память процесса в байтах, либо `null`, если её не узнать. */
    fun residentBytes(pid: Long): Long? {
        val status = File("/proc/$pid/status")
        val text = runCatching { status.readText() }.getOrNull() ?: return null

        val line = text.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val kilobytes = line.removePrefix("VmRSS:").trim().removeSuffix("kB").trim().toLongOrNull()
        return kilobytes?.times(1024)
    }
}
