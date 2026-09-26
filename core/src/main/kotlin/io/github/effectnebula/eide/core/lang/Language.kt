package io.github.effectnebula.eide.core.lang

import io.github.effectnebula.eide.core.editor.Crumb
import io.github.effectnebula.eide.core.editor.IndentRule
import io.github.effectnebula.eide.core.editor.PythonOutline
import io.github.effectnebula.eide.core.text.Rope

/**
 * Что язык меняет в поведении редактора.
 *
 * Пока здесь два поведения: чем отступать новую строку и чем считать путь до
 * строки. Обоим нужен язык, и оба уже жили по своим углам — расчёт крошек в двух
 * точках сборки, отступ в ядре редактора. Третьим местом было бы что-то ещё, и
 * тогда «какой это язык» решалось бы в трёх файлах с расходящимися списками
 * расширений.
 *
 * [outline] может отсутствовать: для языка со скобками расчёт пути по отступам
 * даёт выдуманную вложенность, и лучше не показывать ничего.
 *
 * Это ещё не `LanguageSupport` из плана — в нём должны быть грамматика, LSP и
 * запуск. Подсветка, например, живёт отдельно в `:ui`, потому что отдаёт готовые
 * отрезки цвета. Здесь только то, что уже понадобилось дважды.
 */
data class Language(
    val id: String,
    val extensions: Set<String>,
    val indent: IndentRule,
    val outline: ((Rope, Int) -> List<Crumb>)?,
)

/**
 * Реестр языков — статический.
 *
 * Динамической загрузки плагинов нет и не будет: маркетплейс объявлен антицелью.
 * Новый язык — это запись здесь и модуль рядом.
 */
object Languages {

    val Python = Language(
        id = "python",
        extensions = setOf("py", "pyw"),
        indent = io.github.effectnebula.eide.core.editor.PythonIndent,
        outline = PythonOutline::crumbsAt,
    )

    /**
     * Язык, про который мы ничего не знаем.
     *
     * Отступ повторяется, пути нет. Это не заглушка «на потом», а честный ответ:
     * питоновские правила для файла на C сделали бы хуже, чем их отсутствие.
     */
    val Plain = Language(
        id = "plain",
        extensions = emptySet(),
        indent = IndentRule.CopyPrevious,
        outline = null,
    )

    private val all = listOf(Python)

    fun forFile(name: String): Language {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return Plain
        return all.firstOrNull { extension in it.extensions } ?: Plain
    }
}
