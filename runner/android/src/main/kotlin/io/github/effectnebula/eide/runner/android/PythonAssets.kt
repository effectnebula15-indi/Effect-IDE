package io.github.effectnebula.eide.runner.android

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Распаковка стандартной библиотеки Python из assets на файловую систему.
 *
 * Так предписывает официальная документация по встраиванию Python в Android-приложение:
 * `libpython*.so` едет в jniLibs, а stdlib — в assets, откуда её надо разложить на диск.
 * Расширения из `lib-dynload` интерпретатор потом грузит через `dlopen` из каталога
 * данных приложения — это разрешено; запрет W^X на Android касается `exec` файлов,
 * а не загрузки библиотек.
 *
 * Распаковываем один раз: маркер с версией не даёт делать это на каждом запуске.
 */
internal object PythonAssets {

    private const val TAG = "eide.runner"
    private const val ASSET_ROOT = "python"

    fun ensureExtracted(context: Context, version: String): File {
        val home = File(context.filesDir, ASSET_ROOT)
        val marker = File(home, ".extracted-$version")
        if (marker.isFile) return home

        if (home.exists() && !home.deleteRecursively()) {
            throw IllegalStateException("не удалось очистить $home перед распаковкой")
        }

        val started = System.currentTimeMillis()
        extractDir(context, ASSET_ROOT, context.filesDir)
        marker.writeText(version)
        Log.i(TAG, "stdlib распакована за ${System.currentTimeMillis() - started} мс")

        return home
    }

    private fun extractDir(context: Context, path: String, targetRoot: File) {
        val names = context.assets.list(path)
            ?: throw IllegalStateException("не удалось прочитать assets: $path")
        val targetDir = File(targetRoot, path)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("не удалось создать $targetDir")
        }

        for (name in names) {
            val child = "$path/$name"
            // assets.list не отличает файл от каталога: пробуем открыть как файл,
            // и если не вышло — значит это каталог.
            val stream = runCatching { context.assets.open(child) }.getOrNull()
            if (stream == null) {
                extractDir(context, child, targetRoot)
                continue
            }
            stream.use { input ->
                File(targetDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
