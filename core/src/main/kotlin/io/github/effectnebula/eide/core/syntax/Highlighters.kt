package io.github.effectnebula.eide.core.syntax

/**
 * Какой подсветкой красить файл.
 *
 * Реестр статический и крошечный: динамической загрузки языковых плагинов нет
 * и не планируется — маркетплейс объявлен антицелью. Добавление языка это
 * строчка здесь и лексер рядом.
 */
object Highlighters {

    private val byExtension: Map<String, LineHighlighter> = mapOf(
        "py" to PythonHighlighter,
        "pyi" to PythonHighlighter,
    )

    /**
     * По имени файла. Для незнакомого расширения — [LineHighlighter.None]:
     * лучше без цветов, чем с чужими.
     */
    fun forFile(name: String): LineHighlighter =
        byExtension[name.substringAfterLast('.', "").lowercase()] ?: LineHighlighter.None
}
