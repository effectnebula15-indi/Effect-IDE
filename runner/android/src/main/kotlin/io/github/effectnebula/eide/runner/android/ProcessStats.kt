package io.github.effectnebula.eide.runner.android

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Чтение резидентной памяти процесса из `/proc`.
 *
 * Работает для процессов своего же приложения: они под тем же UID.
 */
internal object ProcessStats {

    private val pageSize: Long by lazy {
        // На устройствах с 16-килобайтными страницами константа 4096 даёт
        // четырёхкратную ошибку, поэтому спрашиваем систему.
        runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
    }

    /** Резидентная память процесса в байтах, либо null, если процесса уже нет. */
    fun residentBytes(pid: Int): Long? {
        val statm = File("/proc/$pid/statm")
        val fields = runCatching { statm.readText() }.getOrNull()?.trim()?.split(' ') ?: return null
        val residentPages = fields.getOrNull(1)?.toLongOrNull() ?: return null
        return residentPages * pageSize
    }
}
