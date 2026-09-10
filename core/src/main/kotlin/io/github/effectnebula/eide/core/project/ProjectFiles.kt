package io.github.effectnebula.eide.core.project

import java.io.File

/** Чем закончился обход: сам дошёл до конца, отменён или остановлен посетителем. */
internal enum class WalkStop { Finished, Cancelled, StoppedByVisitor }

internal class WalkResult(val scanned: Int, val skipped: Int, val stop: WalkStop)

/**
 * Обход всех текстовых файлов проекта.
 *
 * Общий для поиска и замены. Вынесен не ради экономии двух десятков строк, а
 * потому что решения «что считать текстом» и «что пропустить» обязаны совпадать
 * у обоих: замена, которая зайдёт в файл, куда не заходил поиск, — это правка,
 * которую человек не заказывал и не увидит.
 *
 * Обход в ширину, а не в глубину: файлы верхних уровней важнее, и при потолке
 * совпадений в списке окажутся они, а не содержимое самой глубокой папки.
 *
 * Отмена спрашивается на каждой папке, а не на каждом файле: чтение файла и так
 * прерывается быстро, а лишний вызов на каждый файл — это вызов на каждый файл.
 */
internal fun walkSources(
    tree: ProjectTree,
    maxFileBytes: Long,
    isCancelled: () -> Boolean,
    visit: (file: File, relativePath: String, text: String) -> Boolean,
): WalkResult {
    var scanned = 0
    var skipped = 0

    val queue = ArrayDeque<File>()
    queue += tree.root

    while (queue.isNotEmpty()) {
        if (isCancelled()) return WalkResult(scanned, skipped, WalkStop.Cancelled)

        val folder = queue.removeFirst()
        for (entry in tree.children(folder)) {
            when (entry) {
                is ProjectFolder -> queue += entry.file
                is ProjectSource -> {
                    val file = entry.file
                    if (file.length() > maxFileBytes) {
                        skipped++
                        continue
                    }

                    val text = readTextOrNull(file)
                    if (text == null) {
                        skipped++
                        continue
                    }

                    scanned++
                    val path = tree.relativePath(file) ?: file.name
                    if (!visit(file, path, text)) {
                        return WalkResult(scanned, skipped, WalkStop.StoppedByVisitor)
                    }
                }
            }
        }
    }

    return WalkResult(scanned, skipped, WalkStop.Finished)
}

/**
 * Текст файла или null, если это не текст.
 *
 * Двоичные файлы отсеиваются по нулевому байту в начале — тем же признаком,
 * которым пользуется git. Способ грубый: UTF-16 без BOM он посчитает
 * двоичным. Для проекта с кодом это правильный выбор, а не недосмотр:
 * лучше пропустить редкий файл, чем вывалить в список совпадений мусор
 * из середины картинки.
 *
 * Переносы здесь не приводятся к `\n`, в отличие от `TextFiles.load`. Для
 * поиска это неважно — номера строк считаются по `\n`, а `\r` остаётся в конце
 * строки. Для замены важно, и поэтому замена перечитывает файл через
 * `TextFiles`, а не правит эту строку.
 */
private fun readTextOrNull(file: File): String? {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    val head = minOf(bytes.size, BINARY_SNIFF_BYTES)
    for (index in 0 until head) if (bytes[index] == 0.toByte()) return null
    return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
}

private const val BINARY_SNIFF_BYTES = 8000
