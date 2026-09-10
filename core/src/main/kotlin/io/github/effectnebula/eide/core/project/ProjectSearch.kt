package io.github.effectnebula.eide.core.project

import io.github.effectnebula.eide.core.editor.SearchQuery
import io.github.effectnebula.eide.core.editor.TextSearch
import java.io.File

/**
 * Одно совпадение в файле проекта.
 *
 * Строка целиком — чтобы показать её в списке, а не открывать файл ради
 * предпросмотра. Номер строки с нуля, как везде в ядре; на экран он попадает
 * с единицей.
 */
data class ProjectMatch(
    val file: File,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val length: Int,
    val preview: String,
)

/** Чем закончился поиск: полностью, по потолку или отменой. */
enum class SearchOutcome { Complete, LimitReached, Cancelled }

data class ProjectSearchResult(
    val matches: List<ProjectMatch>,
    val outcome: SearchOutcome,
    val filesScanned: Int,
    val filesSkipped: Int,
)

/**
 * Поиск по всем файлам проекта.
 *
 * Живёт в ядре и ничего не знает про потоки: тот, кто зовёт, сам решает, где
 * это исполнять, и сам передаёт признак отмены. Иначе поиск по большому дереву
 * невозможно прервать, а прерывать его нужно на каждой букве в строке запроса.
 *
 * **Почему не индекс.** Индекс — это отдельная подсистема со своим временем
 * жизни, инвалидацией и хранением; для проекта в тысячу файлов честный обход
 * укладывается в доли секунды, а для проекта в сто тысяч файлов эта IDE и не
 * предназначена (ADR-005: потолок файла — десять мегабайт).
 */
class ProjectSearch(private val tree: ProjectTree) {

    fun search(
        query: SearchQuery,
        limit: Int = DEFAULT_LIMIT,
        maxFileBytes: Long = MAX_FILE_BYTES,
        isCancelled: () -> Boolean = { false },
    ): ProjectSearchResult {
        if (query.isEmpty) return ProjectSearchResult(emptyList(), SearchOutcome.Complete, 0, 0)

        val search = TextSearch(query)
        if (search.regex == null) {
            return ProjectSearchResult(emptyList(), SearchOutcome.Complete, 0, 0)
        }

        val matches = ArrayList<ProjectMatch>()
        var scanned = 0
        var skipped = 0
        var outcome = SearchOutcome.Complete

        val queue = ArrayDeque<File>()
        queue += tree.root

        while (queue.isNotEmpty()) {
            if (isCancelled()) return ProjectSearchResult(matches, SearchOutcome.Cancelled, scanned, skipped)

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

                        val text = readText(file)
                        if (text == null) {
                            skipped++
                            continue
                        }

                        scanned++
                        val path = tree.relativePath(file) ?: file.name
                        if (!collect(search, text, file, path, matches, limit)) {
                            return ProjectSearchResult(matches, SearchOutcome.LimitReached, scanned, skipped)
                        }
                    }
                }
            }
        }

        return ProjectSearchResult(matches, outcome, scanned, skipped)
    }

    /** Возвращает false, когда потолок достигнут. */
    private fun collect(
        search: TextSearch,
        text: String,
        file: File,
        path: String,
        into: MutableList<ProjectMatch>,
        limit: Int,
    ): Boolean {
        val regex = search.regex ?: return true

        // Границы строк считаются один раз на файл: искать перевод строки заново
        // для каждого совпадения — это квадрат на файле, где совпадений много.
        val lineStarts = lineStartsOf(text)

        for (found in regex.findAll(text)) {
            val line = lineIndexOf(lineStarts, found.range.first)
            val start = lineStarts[line]
            val end = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length

            into += ProjectMatch(
                file = file,
                relativePath = path,
                line = line,
                column = found.range.first - start,
                length = found.range.last + 1 - found.range.first,
                preview = text.substring(start, end).trim().take(PREVIEW_LIMIT),
            )
            if (into.size >= limit) return false
        }
        return true
    }

    private fun lineStartsOf(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        for (index in text.indices) if (text[index] == '\n') starts += index + 1
        return starts.toIntArray()
    }

    private fun lineIndexOf(starts: IntArray, offset: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (starts[middle] <= offset) low = middle else high = middle - 1
        }
        return low
    }

    /**
     * Текст файла или null, если это не текст.
     *
     * Двоичные файлы отсеиваются по нулевому байту в начале — тем же признаком,
     * которым пользуется git. Способ грубый: UTF-16 без BOM он посчитает
     * двоичным. Для проекта с кодом это правильный выбор, а не недосмотр:
     * лучше пропустить редкий файл, чем вывалить в список совпадений мусор
     * из середины картинки.
     */
    private fun readText(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val head = minOf(bytes.size, BINARY_SNIFF_BYTES)
        for (index in 0 until head) if (bytes[index] == 0.toByte()) return null
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
    }

    companion object {
        /** Потолок совпадений. Больше человек всё равно не просматривает. */
        const val DEFAULT_LIMIT = 500

        /** Файлы больше этого не читаются: ADR-005 и без того ставит потолок в 10 МБ. */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024

        private const val BINARY_SNIFF_BYTES = 8000
        private const val PREVIEW_LIMIT = 200
    }
}
