package io.github.effectnebula.eide.core.project

import java.io.File
import java.nio.file.Files

/** Узел дерева проекта: файл или папка. */
sealed interface ProjectEntry {
    val file: File
    val name: String get() = file.name
}

data class ProjectFolder(override val file: File) : ProjectEntry

data class ProjectSource(override val file: File) : ProjectEntry {
    /** Расширение в нижнем регистре, без точки. Пустое, если его нет. */
    val extension: String get() = file.name.substringAfterLast('.', "").lowercase()
}

/**
 * Дерево файлов проекта.
 *
 * Читает содержимое папки по требованию, а не целиком при открытии. Причина
 * прозаична: в проекте с `node_modules` или `.venv` обход целиком занимает
 * секунды, и делать его на открытии значит подвесить приложение на ровном
 * месте. Цена: дерево не знает, сколько в нём файлов, и «найти по имени» здесь
 * не сделать — это работа индекса проекта, которого пока нет.
 *
 * Слежения за изменениями на диске тоже нет: файловый watcher — платформенная
 * штука, а на Android он ещё и ограничен. Пока обновление ручное, и это видно
 * в интерфейсе, а не подразумевается.
 */
class ProjectTree(val root: File, private val filter: EntryFilter = EntryFilter.Default) {

    /**
     * Что показывать в дереве.
     *
     * Отдельным типом, а не набором флагов: список исключений — часть решения
     * «что человек считает своим проектом», и его придётся менять под язык.
     */
    interface EntryFilter {
        fun accepts(file: File): Boolean

        companion object {
            /**
             * Прячет скрытые файлы и то, что порождают инструменты.
             *
             * Список короткий намеренно: длинный список угадываний раздражает
             * сильнее, чем лишняя папка в дереве. Растёт он вместе с языками,
             * а не заранее.
             */
            val Default: EntryFilter = object : EntryFilter {
                private val hidden = setOf(
                    "__pycache__", ".git", ".gradle", ".idea", "build", ".venv", "venv",
                    "node_modules",
                )

                override fun accepts(file: File): Boolean =
                    !file.name.startsWith(".") && file.name !in hidden
            }

            /** Показывает всё. Нужен, когда человек ищет именно спрятанное. */
            val Everything: EntryFilter = object : EntryFilter {
                override fun accepts(file: File): Boolean = true
            }
        }
    }

    /**
     * Содержимое папки: сначала папки, потом файлы, внутри — по имени.
     *
     * Возвращает пустой список для того, что не папка или недоступно. Это не
     * замалчивание ошибки: право на чтение может пропасть между показом дерева
     * и раскрытием узла, и падать из-за этого нельзя.
     */
    fun children(folder: File): List<ProjectEntry> {
        val entries = folder.listFiles() ?: return emptyList()

        return entries
            .asSequence()
            .filter(filter::accepts)
            .filterNot(::isDirectoryLink)
            .map { if (it.isDirectory) ProjectFolder(it) else ProjectSource(it) }
            .sortedWith(ENTRY_ORDER)
            .toList()
    }

    fun children(folder: ProjectFolder): List<ProjectEntry> = children(folder.file)

    /** Корень как узел — с него начинается показ. */
    fun rootEntry(): ProjectFolder = ProjectFolder(root)

    /**
     * Путь от корня проекта, для показа в заголовке и хлебных крошках.
     *
     * Возвращает `null`, если файл лежит вне проекта: это не ошибка — открыть
     * можно и посторонний файл, но пути от корня у него нет.
     */
    fun relativePath(file: File): String? {
        val rootPath = root.canonicalFile.path
        val filePath = runCatching { file.canonicalFile.path }.getOrNull() ?: return null

        if (filePath == rootPath) return ""
        val prefix = rootPath + File.separator
        if (!filePath.startsWith(prefix)) return null

        return filePath.removePrefix(prefix)
    }

    /**
     * Символическая ссылка на папку не разворачивается.
     *
     * Ссылка на родителя даёт бесконечное дерево, и обнаружится это не сразу, а
     * когда кто-то раскроет узел на десятом уровне. Дешевле не пускать.
     */
    private fun isDirectoryLink(file: File): Boolean =
        file.isDirectory && Files.isSymbolicLink(file.toPath())

    private companion object {
        /**
         * Папки выше файлов, дальше по имени без учёта регистра.
         *
         * Сортировка не «естественная»: `файл10` встанет раньше `файл2`, как в
         * обычном лексикографическом порядке. Это заметно и когда-нибудь будет
         * исправлено; пока предсказуемость важнее.
         */
        val ENTRY_ORDER: Comparator<ProjectEntry> = compareBy<ProjectEntry> { it !is ProjectFolder }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
    }
}
